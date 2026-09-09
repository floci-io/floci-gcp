require_relative "support"

# Run seed, restart the emulator with its data volume, then run verify.
# Separate from all.rb because restarting is controlled by the container runner.
class RestartContractTest < Minitest::Test
  include Fixtures
  def disk_client
    Google::Cloud::Compute::V1::Disks::Rest::Client.new do |c|
      c.endpoint = ENDPOINT
      c.credentials = ->(m) { m.merge(authorization: "Bearer synthetic-sdk-token") }
    end
  end
  def compute_client(name)
    Google::Cloud::Compute::V1.const_get(name)::Rest::Client.new do |c|
      c.endpoint = ENDPOINT
      c.credentials = ->(m) { m.merge(authorization: "Bearer synthetic-sdk-token") }
    end
  end
  def wait(operation)
    deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + 20
    until operation.done?
      flunk "restart fixture operation timed out" if Process.clock_gettime(Process::CLOCK_MONOTONIC) >= deadline
      operation.reload!
      sleep 0.03 unless operation.done?
    end
    raise operation.error if operation.error
    operation
  end
  def released_address_fixture(project)
    compute_client("Networks").insert(project: project, network_resource: {name: "net", auto_create_subnetworks: false}).tap { |operation| wait(operation) }
    compute_client("Subnetworks").insert(project: project, region: "us-central1", subnetwork_resource: {name: "subnet", network: "global/networks/net", ip_cidr_range: "10.75.0.0/24"}).tap { |operation| wait(operation) }
    compute_client("Addresses").insert(project: project, region: "us-central1", address_resource: {name: "released"}).tap { |operation| wait(operation) }
    ip = compute_client("Addresses").get(project: project, region: "us-central1", address: "released").address
    compute_client("Instances").insert(project: project, zone: "us-central1-a", instance_resource: {name: "vm", machine_type: "zones/us-central1-a/machineTypes/n2-standard-4",
      network_interfaces: [{subnetwork: "regions/us-central1/subnetworks/subnet", access_configs: [{nat_i_p: ip}]}],
      disks: [{boot: true, auto_delete: true, initialize_params: {disk_size_gb: 20}}]}).tap { |operation| wait(operation) }
    compute_client("Addresses").delete(project: project, region: "us-central1", address: "released").tap { |operation| wait(operation) }
    compute_client("HealthChecks").insert(project: project, health_check_resource: {name: "health", type: "HTTP"}).tap { |operation| wait(operation) }
    compute_client("BackendServices").insert(project: project, backend_service_resource: {name: "backend", health_checks: ["global/healthChecks/health"]}).tap { |operation| wait(operation) }
    compute_client("UrlMaps").insert(project: project, url_map_resource: {name: "map", default_service: "global/backendServices/backend"}).tap { |operation| wait(operation) }
    compute_client("TargetHttpProxies").insert(project: project, target_http_proxy_resource: {name: "proxy", url_map: "global/urlMaps/map"}).tap { |operation| wait(operation) }
    compute_client("GlobalAddresses").insert(project: project, address_resource: {name: "shared"}).tap { |operation| wait(operation) }
    [80, 8080].each do |port|
      compute_client("GlobalForwardingRules").insert(project: project, forwarding_rule_resource: {name: "frontend-#{port}", I_p_address: "global/addresses/shared",
        I_p_protocol: "TCP", port_range: port.to_s, target: "global/targetHttpProxies/proxy", load_balancing_scheme: "EXTERNAL_MANAGED"}).tap { |operation| wait(operation) }
    end
    ip
  end
  def state_path = ENV.fetch("FLOCI_GCP_RESTART_STATE", "/results/restart.json")
  def test_persisted_operation_disk_and_unfinished_xml_upload
    if ENV.fetch("FLOCI_GCP_RESTART_PHASE") == "seed"
      project = unique("ruby-restart")
      bucket = storage.create_bucket(unique("ruby-restart"))
      upload = http("post", "/#{bucket.name}/pending?uploads=")
      assert_equal "200", upload.code
      id = REXML::Document.new(upload.body).elements["InitiateMultipartUploadResult/UploadId"].text
      part = http("put", "/#{bucket.name}/pending?uploadId=#{id}&partNumber=7", "persistent\x00bytes")
      assert_equal "200", part.code
      ip = released_address_fixture(project)
      op = disk_client.insert(project: project, zone: "us-central1-a", request_id: (request_id = SecureRandom.uuid), disk_resource: {name: "persistent", size_gb: 24})
      File.write(state_path, {project: project, released_ip: ip, bucket: bucket.name, upload: id, etag: part["etag"], operation: op.name, request_id: request_id}.to_json)
    elsif ENV.fetch("FLOCI_GCP_RESTART_PHASE") == "verify"
      state = JSON.parse(File.read(state_path))
      project = state["project"]
      address = compute_client("GlobalAddresses").get(project: project, address: "shared")
      assert_equal "IN_USE", address.status
      assert_equal [80, 8080].map { |port| compute_client("GlobalForwardingRules").get(project: project, forwarding_rule: "frontend-#{port}").self_link }.sort, address.users.to_a.sort
      compute_client("GlobalForwardingRules").delete(project: project, forwarding_rule: "frontend-80").tap { |operation| wait(operation) }
      remaining = compute_client("GlobalForwardingRules").get(project: project, forwarding_rule: "frontend-8080")
      assert_equal [remaining.self_link], compute_client("GlobalAddresses").get(project: project, address: "shared").users.to_a
      compute_client("GlobalAddresses").delete(project: project, address: "shared").tap { |operation| wait(operation) }
      assert_equal address.address, compute_client("GlobalForwardingRules").get(project: project, forwarding_rule: "frontend-8080").I_p_address
      compute_client("GlobalForwardingRules").delete(project: project, forwarding_rule: "frontend-8080").tap { |operation| wait(operation) }
      compute_client("TargetHttpProxies").delete(project: project, target_http_proxy: "proxy").tap { |operation| wait(operation) }
      compute_client("UrlMaps").delete(project: project, url_map: "map").tap { |operation| wait(operation) }
      compute_client("BackendServices").delete(project: project, backend_service: "backend").tap { |operation| wait(operation) }
      compute_client("HealthChecks").delete(project: project, health_check: "health").tap { |operation| wait(operation) }
      assert_raises(Google::Cloud::NotFoundError) { compute_client("Addresses").get(project: project, region: "us-central1", address: "released") }
      assert_equal state["released_ip"], compute_client("Instances").get(project: project, zone: "us-central1-a", instance: "vm").network_interfaces.first.access_configs.first.nat_i_p
      assert_raises(Google::Cloud::InvalidArgumentError) do
        compute_client("Addresses").insert(project: project, region: "us-central1", address_resource: {name: "reuse", address: state["released_ip"]}).tap { |operation| wait(operation) }
      end
      compute_client("Instances").delete(project: project, zone: "us-central1-a", instance: "vm").tap { |operation| wait(operation) }
      compute_client("Addresses").insert(project: project, region: "us-central1", address_resource: {name: "reuse", address: state["released_ip"]}).tap { |operation| wait(operation) }
      compute_client("Addresses").delete(project: project, region: "us-central1", address: "reuse").tap { |operation| wait(operation) }
      compute_client("Subnetworks").delete(project: project, region: "us-central1", subnetwork: "subnet").tap { |operation| wait(operation) }
      compute_client("Networks").delete(project: project, network: "net").tap { |operation| wait(operation) }
      disk = disk_client.get(project: state["project"], zone: "us-central1-a", disk: "persistent")
      assert_equal 24, disk.size_gb
      assert_equal "READY", disk.status
      op = disk_client.insert(project: state["project"], zone: "us-central1-a", request_id: state["request_id"], disk_resource: {name: "persistent", size_gb: 24})
      assert_equal state["operation"], op.name
      assert op.done?
      assert_nil storage.bucket(state["bucket"]).file("pending")
      path = "/#{state['bucket']}/pending?uploadId=#{state['upload']}"
      assert_includes http("get", path).body, "<PartNumber>7</PartNumber>"
      body = "<CompleteMultipartUpload><Part><PartNumber>7</PartNumber><ETag>#{state['etag']}</ETag></Part></CompleteMultipartUpload>"
      assert_equal "200", http("post", path, body).code
      bucket = storage.bucket(state["bucket"])
      assert_equal "persistent\x00bytes", bucket.file("pending").download(StringIO.new).string
      bucket.file("pending").delete
      bucket.delete
      disk_client.delete(project: state["project"], zone: "us-central1-a", disk: "persistent").tap { |operation| wait(operation) }
      assert_raises(Google::Cloud::NotFoundError) { disk_client.get(project: state["project"], zone: "us-central1-a", disk: "persistent") }
    else
      flunk "FLOCI_GCP_RESTART_PHASE must be seed or verify"
    end
  end
end
