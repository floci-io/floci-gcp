"""Soft delete and restore.

On by default in real GCS since 2024, so code written against production may rely on being able
to undo a delete. A deleted object leaves the live listing, appears under ``soft_deleted=True``
with soft/hard delete timestamps, and can be restored by generation.
"""

import pytest


RETENTION_SECONDS = 7 * 24 * 60 * 60


@pytest.fixture
def soft_delete_bucket(storage_client, unique_name):
    bucket = storage_client.create_bucket(
        storage_client.bucket(f"soft-delete-{unique_name}")
    )
    bucket.soft_delete_policy.retention_duration_seconds = RETENTION_SECONDS
    bucket.patch()
    yield bucket
    bucket.delete(force=True)


def test_bucket_reports_its_soft_delete_policy(storage_client, soft_delete_bucket):
    reloaded = storage_client.get_bucket(soft_delete_bucket.name)
    assert reloaded.soft_delete_policy.retention_duration_seconds == RETENTION_SECONDS


def test_deleted_object_leaves_live_listing_and_can_be_restored(
    storage_client, soft_delete_bucket
):
    payload = b"recoverable"
    blob = soft_delete_bucket.blob("restore-me.txt")
    blob.upload_from_string(payload)
    generation = blob.generation

    blob.delete()

    # Gone from the live view.
    assert soft_delete_bucket.get_blob("restore-me.txt") is None
    live = [b.name for b in storage_client.list_blobs(soft_delete_bucket)]
    assert "restore-me.txt" not in live

    # Still visible as soft deleted, with the retention window stamped on it.
    soft_deleted = [
        b
        for b in storage_client.list_blobs(soft_delete_bucket, soft_deleted=True)
        if b.name == "restore-me.txt"
    ]
    assert len(soft_deleted) == 1
    assert soft_deleted[0].soft_delete_time is not None
    assert soft_deleted[0].hard_delete_time is not None

    restored = soft_delete_bucket.restore_blob("restore-me.txt", generation=generation)
    assert restored.name == "restore-me.txt"

    # Back in the live view, with its bytes intact.
    live = [b.name for b in storage_client.list_blobs(soft_delete_bucket)]
    assert "restore-me.txt" in live
    assert soft_delete_bucket.blob("restore-me.txt").download_as_bytes() == payload


def test_delete_is_permanent_without_a_soft_delete_policy(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"hard-{unique_name}"))
    try:
        blob = bucket.blob("gone.txt")
        blob.upload_from_string(b"x")
        blob.delete()

        assert list(storage_client.list_blobs(bucket, soft_deleted=True)) == []
    finally:
        bucket.delete(force=True)
