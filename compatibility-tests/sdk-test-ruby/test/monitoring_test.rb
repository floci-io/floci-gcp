class NetworkUsageContractTest < Minitest::Test
  include Fixtures
  TYPES = %w[compute.googleapis.com/instance/network/sent_bytes_count compute.googleapis.com/instance/network/received_bytes_count].freeze
  def setup
    @project = unique("ruby-metrics")
    endpoint = ENV.fetch("FLOCI_GCP_GRPC_ENDPOINT") { URI(ENDPOINT).then { |u| "#{u.host}:#{u.port}" } }
    @metrics = Google::Cloud::Monitoring::V3::MetricService::Client.new do |c|
      c.endpoint = endpoint
      c.credentials = :this_channel_is_insecure
      c.timeout = 15
    end
  end
  # Google system descriptors cannot be deleted. These synthetic project fixtures
  # are removed with the disposable emulator's state by the contract runner.
  def test_synthetic_network_counters_project_instance_and_time_filters
    now = Time.now.to_i
    TYPES.each_with_index do |type, index|
      @metrics.create_metric_descriptor(name: "projects/#{@project}", metric_descriptor: {
        type: type, metric_kind: :DELTA, value_type: :INT64, unit: "By"})
      ["1001", "1002"].each do |instance|
        @metrics.create_time_series(name: "projects/#{@project}", time_series: [{
          metric: {type: type}, metric_kind: :DELTA, value_type: :INT64,
          resource: {type: "gce_instance", labels: {project_id: @project, instance_id: instance, zone: "us-central1-a"}},
          points: [{interval: {start_time: {seconds: now - 120}, end_time: {seconds: now - 60}}, value: {int64_value: instance == "1001" ? 100 * (index + 1) : 9999}}]
        }])
      end
      filter = %(metric.type = "#{type}" AND resource.labels.instance_id = "1001" AND resource.labels.zone = "us-central1-a")
      rows = @metrics.list_time_series(name: "projects/#{@project}", filter: filter,
        interval: {start_time: {seconds: now - 3600}, end_time: {seconds: now}}, view: :FULL,
        aggregation: {alignment_period: {seconds: 3600}, per_series_aligner: :ALIGN_SUM}, page_size: 1).to_a
      assert_equal 100 * (index + 1), rows.sum { |s| s.points.sum { |p| p.value.int64_value } }
      assert_empty @metrics.list_time_series(name: "projects/#{unique('other')}", filter: filter,
        interval: {start_time: {seconds: now - 3600}, end_time: {seconds: now}}, view: :FULL).to_a
      assert_empty @metrics.list_time_series(name: "projects/#{@project}", filter: filter,
        interval: {start_time: {seconds: now - 30}, end_time: {seconds: now}}, view: :FULL).to_a
    end
  end
end
