"""GCS integration tests using google-cloud-storage."""

import pytest


def test_create_bucket(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.bucket(bucket_name)
    created = storage_client.create_bucket(bucket)
    assert created.name == bucket_name

    # Cleanup
    created.delete(force=True)


def test_upload_and_download_object(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    object_name = "test-object.txt"
    content = "Hello, GCP Cloud Storage from Python!"

    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        blob = bucket.blob(object_name)
        blob.upload_from_string(content, content_type="text/plain")

        downloaded = blob.download_as_text()
        assert downloaded == content

        assert blob.content_type == "text/plain"
        assert blob.size == len(content.encode())
    finally:
        bucket.delete(force=True)


def test_download_populates_blob_from_response_headers(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    object_name = "header-object.txt"
    content = b"header extraction content"

    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        bucket.blob(object_name).upload_from_string(content, content_type="text/plain")
        canonical = bucket.get_blob(object_name)

        # A fresh handle carries no properties. download_as_bytes fills them
        # from the x-goog-* headers of the media response, and
        # checksum="crc32c" verifies the payload against x-goog-hash.
        blob = bucket.blob(object_name)
        assert blob.download_as_bytes(checksum="crc32c") == content

        assert blob.generation == canonical.generation
        assert blob.metageneration == canonical.metageneration
        assert blob.crc32c == canonical.crc32c
        assert blob.md5_hash == canonical.md5_hash
        assert blob.storage_class == canonical.storage_class
        assert blob.etag == canonical.etag
    finally:
        bucket.delete(force=True)


def test_object_custom_metadata(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        blob = bucket.blob("meta-object.txt")
        blob.metadata = {"originalname": "test.txt", "reviewer": "jane"}
        blob.upload_from_string("metadata content", content_type="text/plain")

        fetched = bucket.get_blob("meta-object.txt")
        assert fetched.metadata == {"originalname": "test.txt", "reviewer": "jane"}
        assert fetched.download_as_text() == "metadata content"
    finally:
        bucket.delete(force=True)


def test_list_objects_in_bucket(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        bucket.blob("file1.txt").upload_from_string("data1")
        bucket.blob("file2.txt").upload_from_string("data2")

        blobs = list(storage_client.list_blobs(bucket_name))
        names = [b.name for b in blobs]
        assert "file1.txt" in names
        assert "file2.txt" in names
    finally:
        bucket.delete(force=True)


def test_copy_object(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        src = bucket.blob("source.txt")
        src.upload_from_string("copy me")

        bucket.copy_blob(src, bucket, "copy.txt")

        dst = bucket.blob("copy.txt")
        assert dst.download_as_text() == "copy me"
    finally:
        bucket.delete(force=True)


def test_delete_object(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        blob = bucket.blob("to-delete.txt")
        blob.upload_from_string("delete me")
        blob.delete()

        assert not bucket.blob("to-delete.txt").exists()
    finally:
        bucket.delete(force=True)


def test_list_buckets(storage_client, unique_name):
    bucket_name = f"test-bucket-{unique_name}"
    bucket = storage_client.create_bucket(storage_client.bucket(bucket_name))

    try:
        buckets = [b.name for b in storage_client.list_buckets()]
        assert bucket_name in buckets
    finally:
        bucket.delete(force=True)
