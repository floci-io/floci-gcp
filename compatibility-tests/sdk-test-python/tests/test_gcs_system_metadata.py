"""System metadata set at upload time, and the storage class inherited from the bucket.

The metageneration assertion is the point of the first test: these fields have to land with the
object rather than being patched on afterwards, otherwise every upload reports a spurious
metageneration bump and clients that branch on it see a phantom change.
"""

import datetime

import pytest


def test_system_metadata_survives_upload_without_metageneration_bump(
    storage_client, unique_name
):
    bucket = storage_client.create_bucket(storage_client.bucket(f"sysmeta-{unique_name}"))
    try:
        custom_time = datetime.datetime(2026, 3, 1, 12, 0, tzinfo=datetime.timezone.utc)
        blob = bucket.blob("system-metadata.txt")
        blob.content_disposition = 'attachment; filename="report.txt"'
        blob.content_language = "en"
        blob.cache_control = "public, max-age=3600"
        blob.custom_time = custom_time
        blob.upload_from_string(b"body", content_type="text/plain")

        assert blob.content_disposition == 'attachment; filename="report.txt"'
        assert blob.content_language == "en"
        assert blob.cache_control == "public, max-age=3600"
        assert blob.metageneration == 1

        # Re-read to confirm the fields are persisted rather than echoed from the request.
        reread = bucket.get_blob("system-metadata.txt")
        assert reread.content_disposition == 'attachment; filename="report.txt"'
        assert reread.content_language == "en"
        assert reread.cache_control == "public, max-age=3600"
        assert reread.custom_time == custom_time
        assert reread.metageneration == 1
    finally:
        bucket.delete(force=True)


def test_custom_time_can_be_set_by_patch_after_upload(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"customtime-{unique_name}"))
    try:
        blob = bucket.blob("patched.txt")
        blob.upload_from_string(b"")

        custom_time = datetime.datetime(2026, 4, 2, 8, 30, tzinfo=datetime.timezone.utc)
        blob.custom_time = custom_time
        blob.patch()

        assert bucket.get_blob("patched.txt").custom_time == custom_time
    finally:
        bucket.delete(force=True)


def test_object_inherits_bucket_default_storage_class(storage_client, unique_name):
    bucket = storage_client.bucket(f"nearline-{unique_name}")
    bucket.storage_class = "NEARLINE"
    bucket = storage_client.create_bucket(bucket)
    try:
        inherited = bucket.blob("inherited.txt")
        inherited.upload_from_string(b"")
        assert bucket.get_blob("inherited.txt").storage_class == "NEARLINE"

        # An explicit storage class on the upload still wins over the bucket default.
        explicit = bucket.blob("explicit.txt")
        explicit.storage_class = "COLDLINE"
        explicit.upload_from_string(b"")
        assert bucket.get_blob("explicit.txt").storage_class == "COLDLINE"
    finally:
        bucket.delete(force=True)


def test_zero_byte_object_round_trips(storage_client, unique_name):
    # Directory placeholders, Spark _SUCCESS markers and .keep files are all empty objects.
    bucket = storage_client.create_bucket(storage_client.bucket(f"empty-{unique_name}"))
    try:
        blob = bucket.blob("empty/_SUCCESS")
        blob.upload_from_string(b"")

        assert bucket.get_blob("empty/_SUCCESS").size == 0
        assert blob.download_as_bytes() == b""
    finally:
        bucket.delete(force=True)
