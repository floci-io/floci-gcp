"""objects.rewrite, single-shot and chunked.

Real GCS makes rewrite multi-call: when ``maxBytesRewrittenPerCall`` is below the object size
the response comes back ``done: false`` with a ``rewriteToken`` and the client loops until it
completes. Per the storage/v1 discovery document the limit "only applies to requests where the
source and destination span locations and/or storage classes" and "must be an integral multiple
of 1 MiB", so the chunked case changes the storage class and a same-class copy is asserted to
finish in one call.

google-cloud-storage 3.x never sends ``maxBytesRewrittenPerCall`` (``Blob.rewrite`` has no
parameter for it), so the chunked loop is driven over raw HTTP here while the SDK covers the
single-call path and the token round-trip shape it would consume.
"""

import json
import urllib.error
import urllib.request

import pytest


PAYLOAD = bytes(i % 251 for i in range(3 * 1024 * 1024))
ONE_MIB = 1024 * 1024


@pytest.fixture
def rewrite_bucket(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"rewrite-{unique_name}"))
    bucket.blob("large-source.bin").upload_from_string(PAYLOAD)
    yield bucket
    bucket.delete(force=True)


def rewrite_call(
    storage_emulator_host, bucket, source, target, max_bytes, token=None, storage_class=None
):
    url = (
        f"{storage_emulator_host}/storage/v1/b/{bucket.name}/o/{source}"
        f"/rewriteTo/b/{bucket.name}/o/{target}?maxBytesRewrittenPerCall={max_bytes}"
    )
    if token:
        url += f"&rewriteToken={token}"
    body = json.dumps({"storageClass": storage_class}).encode() if storage_class else b""
    headers = {"Content-Type": "application/json"} if storage_class else {}
    request = urllib.request.Request(url, data=body, method="POST", headers=headers)
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def test_chunked_rewrite_completes_over_several_calls(
    storage_emulator_host, rewrite_bucket
):
    # The destination changes storage class, so the copy spans classes and the limit applies.
    first = rewrite_call(
        storage_emulator_host,
        rewrite_bucket,
        "large-source.bin",
        "chunked.bin",
        ONE_MIB,
        storage_class="NEARLINE",
    )

    assert first["kind"] == "storage#rewriteResponse"
    assert first["done"] is False
    assert first["rewriteToken"]
    assert int(first["objectSize"]) == len(PAYLOAD)
    assert 0 < int(first["totalBytesRewritten"]) < len(PAYLOAD)
    assert "resource" not in first

    # A partially rewritten object must not be visible: GCS publishes the destination only on
    # the completing call.
    assert rewrite_bucket.get_blob("chunked.bin") is None

    response = first
    calls = 1
    while not response["done"]:
        response = rewrite_call(
            storage_emulator_host,
            rewrite_bucket,
            "large-source.bin",
            "chunked.bin",
            ONE_MIB,
            token=response["rewriteToken"],
        )
        calls += 1

    assert calls == 3
    assert int(response["totalBytesRewritten"]) == len(PAYLOAD)
    assert response["resource"]["name"] == "chunked.bin"
    assert response["resource"]["storageClass"] == "NEARLINE"
    assert rewrite_bucket.blob("chunked.bin").download_as_bytes() == PAYLOAD


def test_limit_is_ignored_for_a_same_class_copy(storage_emulator_host, rewrite_bucket):
    # Same bucket, same class, same location: real GCS completes this in one call regardless of
    # maxBytesRewrittenPerCall, and so does the emulator.
    response = rewrite_call(
        storage_emulator_host, rewrite_bucket, "large-source.bin", "same-class.bin", ONE_MIB
    )
    assert response["done"] is True
    assert "rewriteToken" not in response
    assert int(response["totalBytesRewritten"]) == len(PAYLOAD)


def test_limit_must_be_a_multiple_of_one_mib(storage_emulator_host, rewrite_bucket):
    with pytest.raises(urllib.error.HTTPError) as error:
        rewrite_call(
            storage_emulator_host,
            rewrite_bucket,
            "large-source.bin",
            "bad-limit.bin",
            1024,
            storage_class="NEARLINE",
        )
    assert error.value.code == 400


def test_sdk_rewrite_completes_in_one_call(rewrite_bucket):
    source = rewrite_bucket.blob("large-source.bin")
    target = rewrite_bucket.blob("single-shot.bin")

    token, rewritten, total = target.rewrite(source)

    # No token means done; the SDK's caller loop exits on exactly this.
    assert token is None
    assert rewritten == total == len(PAYLOAD)
    assert rewrite_bucket.blob("single-shot.bin").download_as_bytes() == PAYLOAD
