require_relative "support"

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
