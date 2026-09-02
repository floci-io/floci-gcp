"""Decompressive transcoding.

An object stored with ``contentEncoding: gzip`` is served decompressed to a client that did not
ask for gzip, and as stored to one that did. Range is ignored on a transcoded read, because
stored offsets do not correspond to the bytes the caller receives.

The raw cases use urllib so the exact ``Accept-Encoding`` sent and the exact bytes returned are
under the test's control; an HTTP client that negotiates compression on the caller's behalf
would hide both.
"""

import gzip
import urllib.request

import pytest


TEXT = b"transcoding payload " * 64


@pytest.fixture
def gzipped_object(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"transcode-{unique_name}"))
    compressed = gzip.compress(TEXT)
    blob = bucket.blob("compressed.txt")
    blob.content_encoding = "gzip"
    blob.upload_from_string(compressed, content_type="text/plain")
    yield bucket, blob, compressed
    bucket.delete(force=True)


def media_url(storage_emulator_host, bucket, blob):
    return f"{storage_emulator_host}/storage/v1/b/{bucket.name}/o/{blob.name}?alt=media"


def test_client_that_does_not_ask_for_gzip_gets_decompressed_bytes(
    storage_emulator_host, gzipped_object
):
    bucket, blob, _ = gzipped_object
    request = urllib.request.Request(media_url(storage_emulator_host, bucket, blob))
    with urllib.request.urlopen(request) as response:
        body = response.read()
        # A transcoded response is no longer gzip on the wire, so it must not claim to be.
        assert response.headers.get("Content-Encoding") is None

    assert body == TEXT


def test_client_that_asks_for_gzip_gets_the_stored_bytes(
    storage_emulator_host, gzipped_object
):
    bucket, blob, compressed = gzipped_object
    request = urllib.request.Request(
        media_url(storage_emulator_host, bucket, blob),
        headers={"Accept-Encoding": "gzip"},
    )
    with urllib.request.urlopen(request) as response:
        body = response.read()
        assert response.headers.get("Content-Encoding") == "gzip"

    assert body == compressed


def test_range_is_ignored_on_a_transcoded_read(storage_emulator_host, gzipped_object):
    bucket, blob, _ = gzipped_object
    request = urllib.request.Request(
        media_url(storage_emulator_host, bucket, blob), headers={"Range": "bytes=0-9"}
    )
    with urllib.request.urlopen(request) as response:
        assert response.status == 200
        assert response.read() == TEXT


def test_sdk_raw_download_returns_the_stored_bytes(gzipped_object):
    # raw_download=True is how the SDK asks for the object exactly as stored.
    _, blob, compressed = gzipped_object
    assert blob.download_as_bytes(raw_download=True) == compressed
