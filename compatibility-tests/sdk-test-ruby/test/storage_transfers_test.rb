require "openssl"

class StorageTransfersContractTest < Minitest::Test
  include Fixtures
  def setup
    @bucket = storage.create_bucket(unique("ruby-transfers"))
  end
  def teardown
    @bucket.files.all { |file| file.delete }
    @bucket.delete
  end
  def test_resumable_range_signed_transfers_and_downscoped_prefix
    bytes = (0..255).to_a.pack("C*") * 4096
    @bucket.create_file(StringIO.new(bytes), "allowed/stream", metadata: {purpose: "contract"})
    file = @bucket.file("allowed/stream")
    assert_equal bytes, file.download(StringIO.new).string.b
    assert_equal "contract", file.metadata["purpose"]
    assert_equal bytes.byteslice(5, 21), file.download(StringIO.new, range: 5..25).string.b
    key = OpenSSL::PKey::RSA.new(2048)
    signer = ->(value) { key.sign(OpenSSL::Digest::SHA256.new, value) }
    url = @bucket.signed_url("allowed/signed", method: "PUT", version: :v4, expires: 900,
      issuer: "fixture@example.invalid", signer: signer)
    assert_equal "200", http("put", URI(url).request_uri, bytes).code
    assert_equal bytes, @bucket.file("allowed/signed").download(StringIO.new).string.b
    download = @bucket.signed_url("allowed/signed", method: "GET", version: :v4, expires: 900,
      issuer: "fixture@example.invalid", signer: signer)
    assert_equal bytes, http("get", URI(download).request_uri).body.b
    # Expiry validation is distinct from the emulator's missing signature verification.
    expired = URI(download).request_uri.sub(/X-Goog-Date=[^&]+/, "X-Goog-Date=20000101T000000Z")
    assert_includes http("get", expired).body, "ExpiredToken"
    boundary = {accessBoundary: {accessBoundaryRules: [{
      availableResource: "//storage.googleapis.com/projects/_/buckets/#{@bucket.name}",
      availablePermissions: ["inRole:roles/storage.objectViewer", "inRole:roles/storage.legacyBucketWriter"],
      availabilityCondition: {expression: "resource.name.startsWith('projects/_/buckets/#{@bucket.name}/objects/allowed/') || api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith('allowed/')"}
    }]}}
    exchange = http("post", "/v1/token", URI.encode_www_form(
      grant_type: "urn:ietf:params:oauth:grant-type:token-exchange", subject_token: "synthetic-source-token",
      subject_token_type: "urn:ietf:params:oauth:token-type:access_token",
      requested_token_type: "urn:ietf:params:oauth:token-type:access_token", options: boundary.to_json),
      {"Content-Type" => "application/x-www-form-urlencoded"})
    assert_equal "200", exchange.code, exchange.body
    credentials = Google::Auth::BearerTokenCredentials.new(token: JSON.parse(exchange.body).fetch("access_token"))
    scoped = Google::Cloud::Storage.new(project_id: PROJECT, credentials: credentials, endpoint: ENDPOINT + "/")
    bucket = scoped.bucket(@bucket.name, skip_lookup: true)
    assert_equal bytes, bucket.file("allowed/stream").download(StringIO.new).string.b
    bucket.create_file(StringIO.new("grant"), "allowed/grant")
    assert_raises(Google::Cloud::PermissionDeniedError) { bucket.create_file(StringIO.new("deny"), "outside/grant") }
    assert_raises(Google::Cloud::PermissionDeniedError) { bucket.files(prefix: "outside/").all.to_a }
  end
end
