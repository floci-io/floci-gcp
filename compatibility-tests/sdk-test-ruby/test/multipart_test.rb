require "openssl"
require "cgi"

class MultipartContractTest < Minitest::Test
  include Fixtures
  def setup
    @bucket = storage.create_bucket(unique("ruby-xml"))
    @key = "nested/bytes.bin"
    @uploads = []
    @signing_key = OpenSSL::PKey::RSA.new(2048)
  end
  def teardown
    @uploads.each { |id| request("DELETE", {"uploadId" => id}) }
    @bucket.files.all { |file| file.delete }
    @bucket.delete
  end
  def request(method, query, body = nil)
    url = @bucket.signed_url(@key, method: method, version: :v4, expires: 900, query: query,
      issuer: "fixture@example.invalid", signer: ->(value) { @signing_key.sign(OpenSSL::Digest::SHA256.new, value) })
    # Keep the SDK's native path and signed query, sending only to the configured emulator.
    # This probes wire compatibility and expiry, not cryptographic signature enforcement.
    uri = URI(url)
    http(method.downcase, uri.request_uri, body)
  end
  def initiate
    result = request("POST", {"uploads" => ""})
    assert_equal "200", result.code, result.body
    id = REXML::Document.new(result.body).elements["InitiateMultipartUploadResult/UploadId"].text
    @uploads << id
    id
  end
  def test_signed_multipart_bytes_retries_abort_and_receipts
    id = initiate
    bytes = "\x00\xffsample".b * (750_000)
    first = request("PUT", {"uploadId" => id, "partNumber" => "1"}, bytes)
    assert_equal "200", first.code, first.body
    tail = request("PUT", {"uploadId" => id, "partNumber" => "2"}, "tail")
    assert_equal "200", tail.code, tail.body
    assert_equal first["etag"], request("PUT", {"uploadId" => id, "partNumber" => "1"}, bytes)["etag"]
    assert_nil @bucket.file(@key)
    part = ->(n, token) { "<Part><PartNumber>#{n}</PartNumber><ETag>#{CGI.escapeHTML(token)}</ETag></Part>" }
    bad = request("POST", {"uploadId" => id}, "<CompleteMultipartUpload>#{part.call(1, 'wrong')}</CompleteMultipartUpload>")
    assert_equal "400", bad.code
    complete = request("POST", {"uploadId" => id}, "<CompleteMultipartUpload>#{part.call(1, first['etag'])}#{part.call(2, tail['etag'])}</CompleteMultipartUpload>")
    assert_equal "200", complete.code, complete.body
    @uploads.delete(id)
    assert_equal bytes + "tail", @bucket.file(@key).download(StringIO.new).string.b
    abort = initiate
    assert_equal "204", request("DELETE", {"uploadId" => abort}).code
    @uploads.delete(abort)
    assert_equal "404", request("GET", {"uploadId" => abort}).code
  end
end
