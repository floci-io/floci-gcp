require "minitest/autorun"
require "minitest/reporters"
require "google/cloud/storage"
require "google/cloud/compute/v1"
require "google/cloud/monitoring/v3"
require "securerandom"
require "stringio"
require "net/http"
require "json"
require "rexml/document"

Minitest::Reporters.use! [Minitest::Reporters::DefaultReporter.new,
  Minitest::Reporters::JUnitReporter.new(ENV.fetch("RESULTS_DIR", "/results"), false)]

module Fixtures
  ENDPOINT = ENV.fetch("FLOCI_GCP_ENDPOINT", "http://localhost:4588").delete_suffix("/")
  PROJECT = ENV.fetch("FLOCI_GCP_PROJECT", "ruby-compat")
  def unique(prefix) = "#{prefix}-#{SecureRandom.hex(6)}"
  def storage
    Google::Cloud::Storage.new(project_id: PROJECT, credentials: :this_channel_is_insecure,
      endpoint: ENDPOINT)
  end
  def http(method, path, body = nil, headers = {})
    uri = URI(ENDPOINT + path)
    request = Net::HTTP.const_get(method.capitalize).new(uri)
    headers.each { |k, v| request[k] = v }
    request.body = body if body
    Net::HTTP.start(uri.host, uri.port, open_timeout: 5, read_timeout: 15) { |c| c.request(request) }
  end
end

class StorageContractTest < Minitest::Test
  include Fixtures
  def setup
    @bucket = storage.create_bucket(unique("ruby-contract"))
  end
  def teardown
    @bucket&.files&.all { |file| file.delete }
    @bucket&.delete
  end
  def test_bytes_metadata_and_inventory
    bytes = "\x00\xffbinary\n".b * 100
    file = @bucket.create_file(StringIO.new(bytes), "nested/data.bin", content_type: "application/octet-stream")
    assert_equal bytes.bytesize, file.size
    assert_equal bytes, file.download(StringIO.new).string.b
    @bucket.create_file(StringIO.new("second"), "nested/second.txt")
    assert_equal ["nested/data.bin", "nested/second.txt"], @bucket.files(prefix: "nested/", max: 1).all.map(&:name).sort
    assert_nil @bucket.file("missing")
  end
end

Dir[File.join(__dir__, "*_test.rb")].sort.each { |path| require path }
