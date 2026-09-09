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
      op = disk_client.insert(project: project, zone: "us-central1-a", request_id: (request_id = SecureRandom.uuid), disk_resource: {name: "persistent", size_gb: 24})
      File.write(state_path, {project: project, bucket: bucket.name, upload: id, etag: part["etag"], operation: op.name, request_id: request_id}.to_json)
    elsif ENV.fetch("FLOCI_GCP_RESTART_PHASE") == "verify"
      state = JSON.parse(File.read(state_path))
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
      disk_client.delete(project: state["project"], zone: "us-central1-a", disk: "persistent").wait_until_done!
      assert_raises(Google::Cloud::NotFoundError) { disk_client.get(project: state["project"], zone: "us-central1-a", disk: "persistent") }
    else
      flunk "FLOCI_GCP_RESTART_PHASE must be seed or verify"
    end
  end
end
