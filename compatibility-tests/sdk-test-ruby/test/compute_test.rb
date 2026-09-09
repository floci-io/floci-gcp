class ComputeContractTest < Minitest::Test
  include Fixtures
  ZONE = "us-central1-a"
  REGION = "us-central1"
  def setup
    @project = unique("ruby-compute")
    @clients = {}
    @owned = []
  end
  def client(name)
    @clients[name] ||= Google::Cloud::Compute::V1.const_get(name)::Rest::Client.new do |c|
      c.endpoint = ENDPOINT
      c.credentials = ->(metadata) { metadata.merge(authorization: "Bearer synthetic-sdk-token") }
      c.timeout = 15
    end
  end
  def wait(op)
    deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 20
    until op.done?
      flunk "operation polling timed out" if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
      op.reload!
      sleep 0.03 unless op.done?
    end
    assert op.done?, "operation did not complete"
    raise op.error if op.error
    op
  end
  def create(collection, singular, body, scope = {})
    wait client(collection).insert({project: @project, "#{singular}_resource".to_sym => body}.merge(scope))
    @owned << [collection, singular, body.fetch(:name), scope]
    body.fetch(:name)
  end
  def teardown
    failures = []
    @owned.reverse_each do |collection, singular, name, scope|
      begin
        wait client(collection).delete({project: @project, singular.to_sym => name}.merge(scope))
      rescue Google::Cloud::NotFoundError
        # Explicit deletion in the journey already removed this resource.
      rescue StandardError => e
        failures << "#{collection}/#{name}: #{e.message}"
      end
    end
    assert_empty failures, failures.join("\n")
  end
  def network
    create("Networks", "network", {name: "net", auto_create_subnetworks: false})
    create("Subnetworks", "subnetwork", {name: "subnet", network: "global/networks/net", ip_cidr_range: "10.30.0.0/24"}, region: REGION)
  end
  def vm(name = "vm")
    create("Instances", "instance", {name: name, machine_type: "zones/#{ZONE}/machineTypes/n2-standard-4",
      network_interfaces: [{subnetwork: "regions/#{REGION}/subnetworks/subnet"}],
      disks: [{boot: true, auto_delete: true, initialize_params: {disk_size_gb: 20}}]}, zone: ZONE)
  end
  def test_compute_lifecycle_images_retained_disks_and_errors
    network
    vm
    args = {project: @project, zone: ZONE, instance: "vm"}
    assert_equal "RUNNING", client("Instances").get(args).status
    current = client("Instances").get(args)
    wait client("Instances").set_labels(**args, instances_set_labels_request_resource: {labels: {purpose: "contract"}, label_fingerprint: current.label_fingerprint})
    assert_raises(Google::Cloud::Error) do
      client("Instances").set_labels(**args, instances_set_labels_request_resource: {labels: {}, label_fingerprint: current.label_fingerprint})
    end
    wait client("Instances").set_metadata(**args, metadata_resource: {fingerprint: current.metadata.fingerprint, items: [{key: "startup-script", value: "echo synthetic"}]})
    wait client("Instances").set_tags(**args, tags_resource: {fingerprint: current.tags.fingerprint, items: ["desktop"]})
    assert_equal "echo synthetic", client("Instances").get(args).metadata.items.first.value
    assert_equal ["desktop"], client("Instances").get(args).tags.items.to_a
    assert_raises(Google::Cloud::Error) { client("Images").insert(project: @project, image_resource: {name: "busy", source_disk: "zones/#{ZONE}/disks/vm"}) }
    wait client("Images").insert(project: @project, force_create: true, image_resource: {name: "forced", source_disk: "zones/#{ZONE}/disks/vm"})
    @owned << ["Images", "image", "forced", {}]
    assert_raises(Google::Cloud::Error) do
      client("Instances").set_machine_type(**args, instances_set_machine_type_request_resource: {machine_type: "zones/#{ZONE}/machineTypes/n2-standard-8"})
    end
    wait client("Instances").stop(args)
    wait client("Instances").set_machine_type(**args, instances_set_machine_type_request_resource: {machine_type: "zones/#{ZONE}/machineTypes/n2-standard-8"})
    wait client("Instances").start(args)
    wait client("Instances").reset(args)
    create("Disks", "disk", {name: "data", size_gb: 100, type: "zones/#{ZONE}/diskTypes/hyperdisk-balanced", provisioned_iops: 4000, provisioned_throughput: 200}, zone: ZONE)
    wait client("Disks").update(project: @project, zone: ZONE, disk: "data", disk_resource: {provisioned_iops: 6000, provisioned_throughput: 300})
    assert_equal 6000, client("Disks").get(project: @project, zone: ZONE, disk: "data").provisioned_iops
    wait client("Instances").attach_disk(**args, attached_disk_resource: {source: "zones/#{ZONE}/disks/data", device_name: "data"})
    assert_raises(Google::Cloud::Error) { client("Disks").delete(project: @project, zone: ZONE, disk: "data") }
    wait client("Instances").detach_disk(**args, device_name: "data")
    create("Snapshots", "snapshot", {name: "snapshot", source_disk: "zones/#{ZONE}/disks/data"})
    snap = client("Snapshots").get(project: @project, snapshot: "snapshot")
    wait client("Snapshots").set_labels(project: @project, resource: "snapshot", global_set_labels_request_resource: {label_fingerprint: snap.label_fingerprint, labels: {purpose: "restore"}})
    assert_equal "restore", client("Snapshots").get(project: @project, snapshot: "snapshot").labels["purpose"]
    assert_raises(Google::Cloud::NotFoundError) { client("Disks").insert(project: @project, zone: ZONE, disk_resource: {name: "missing-source", source_snapshot: "global/snapshots/missing"}) }
    create("Disks", "disk", {name: "restored", source_snapshot: "global/snapshots/snapshot"}, zone: "us-central1-b")
    assert_equal 100, client("Disks").get(project: @project, zone: "us-central1-b", disk: "restored").size_gb
    create("Images", "image", {name: "image", family: "desktop", source_disk: "zones/#{ZONE}/disks/data"})
    create("Images", "image", {name: "copied", family: "desktop", source_image: "global/images/image"})
    assert_equal "copied", client("Images").get_from_family(project: @project, family: "desktop").name
    assert_equal %w[copied forced image], client("Images").list(project: @project, max_results: 1).map(&:name).sort
    assert_raises(Google::Cloud::NotFoundError) { client("Instances").get(project: unique("other"), zone: ZONE, instance: "vm") }
    wait client("Instances").delete(args)
    assert_equal "READY", client("Disks").get(project: @project, zone: ZONE, disk: "data").status
    assert_raises(Google::Cloud::NotFoundError) { client("Disks").get(project: @project, zone: ZONE, disk: "vm") }
  end
  def test_deduplication_numeric_ids_scoped_operations_and_catalogs
    args = {project: @project, request_id: SecureRandom.uuid, network_resource: {name: "dedup", auto_create_subnetworks: false}}
    first = client("Networks").insert(args)
    second = client("Networks").insert(args)
    assert_equal first.name, second.name
    wait first
    @owned << ["Networks", "network", "dedup", {}]
    op = client("GlobalOperations").wait(project: @project, operation: first.name)
    assert_equal :DONE, op.status
    assert_equal first.name, client("GlobalOperations").list(project: @project).first.name
    assert_raises(Google::Cloud::NotFoundError) { client("ZoneOperations").get(project: @project, zone: ZONE, operation: first.name) }
    net = client("Networks").get(project: @project, network: "dedup")
    wait client("Networks").delete(project: @project, network: net.id.to_s)
    assert_raises(Google::Cloud::NotFoundError) { client("Networks").get(project: @project, network: "dedup") }
    client("GlobalOperations").delete(project: @project, operation: first.name)
    assert_raises(Google::Cloud::NotFoundError) { client("GlobalOperations").get(project: @project, operation: first.name) }
    assert_includes client("MachineTypes").list(project: @project, zone: ZONE).map(&:name), "n2-standard-4"
    assert_includes client("DiskTypes").list(project: @project, zone: ZONE).map(&:name), "hyperdisk-balanced"
    assert_includes client("Zones").list(project: @project).map(&:name), ZONE
  end
  def test_global_routing_and_shared_path_updates
    network
    vm
    create("NetworkEndpointGroups", "network_endpoint_group", {name: "neg", network_endpoint_type: "GCE_VM_IP_PORT", network: "global/networks/net", subnetwork: "regions/#{REGION}/subnetworks/subnet"}, zone: ZONE)
    neg_args = {project: @project, zone: ZONE, network_endpoint_group: "neg"}
    wait client("NetworkEndpointGroups").attach_network_endpoints(**neg_args, network_endpoint_groups_attach_endpoints_request_resource: {network_endpoints: [{instance: "vm", port: 8080}]})
    create("HealthChecks", "health_check", {name: "health", type: "HTTP", http_health_check: {port: 8081}})
    create("BackendServices", "backend_service", {name: "backend", protocol: "HTTP", load_balancing_scheme: "EXTERNAL_MANAGED", health_checks: ["global/healthChecks/health"], backends: [{group: "zones/#{ZONE}/networkEndpointGroups/neg", balancing_mode: "RATE", max_rate_per_endpoint: 100}]})
    matcher = {name: "routes", default_service: "global/backendServices/backend", path_rules: [{paths: ["/one/*", "/two/*"], service: "global/backendServices/backend"}]}
    create("UrlMaps", "url_map", {name: "routes", default_service: "global/backendServices/backend", path_matchers: [matcher], host_rules: [{hosts: ["*"], path_matcher: "routes"}]})
    create("TargetHttpProxies", "target_http_proxy", {name: "proxy", url_map: "global/urlMaps/routes"})
    create("GlobalAddresses", "address", {name: "frontend"})
    create("GlobalForwardingRules", "forwarding_rule", {name: "frontend", target: "global/targetHttpProxies/proxy", I_p_address: "global/addresses/frontend", I_p_protocol: "TCP", port_range: "80", load_balancing_scheme: "EXTERNAL_MANAGED"})
    current = client("UrlMaps").get(project: @project, url_map: "routes")
    matcher[:path_rules][0][:paths] = ["/two/*"]
    wait client("UrlMaps").patch(project: @project, url_map: "routes", url_map_resource: {fingerprint: current.fingerprint, path_matchers: [matcher]})
    assert_equal ["/two/*"], client("UrlMaps").get(project: @project, url_map: "routes").path_matchers.first.path_rules.first.paths.to_a
    assert_equal "backend", client("BackendServices").get(project: @project, backend_service: "backend").name
    endpoints = client("NetworkEndpointGroups").list_network_endpoints(**neg_args, network_endpoint_groups_list_endpoints_request_resource: {})
    assert_equal 8080, endpoints.first.network_endpoint.port
    wait client("NetworkEndpointGroups").detach_network_endpoints(**neg_args, network_endpoint_groups_detach_endpoints_request_resource: {network_endpoints: [{instance: "vm", port: 8080}]})
  end
end
