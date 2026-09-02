"""Conditional reads on objects.get.

The two precondition families fail differently: a ``*Match`` that does not hold is 412, while a
``*NotMatch`` that does not hold is 304 Not Modified, which is the whole point of sending it , 
the caller already has the body and wants to skip the transfer.

Driven over raw HTTP because the assertion is on the status code itself, and the SDK collapses
a 304 into a cached value rather than surfacing it.
"""

import urllib.error
import urllib.request

import pytest


@pytest.fixture
def conditional_object(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"conditional-{unique_name}"))
    blob = bucket.blob("conditional.txt")
    blob.upload_from_string(b"conditional body")
    yield bucket, blob
    bucket.delete(force=True)


def status(storage_emulator_host, bucket, blob, query):
    url = (
        f"{storage_emulator_host}/storage/v1/b/{bucket.name}/o/{blob.name}?{query}"
    )
    try:
        with urllib.request.urlopen(url) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def test_not_match_that_holds_false_returns_304(
    storage_emulator_host, conditional_object
):
    bucket, blob = conditional_object
    assert status(
        storage_emulator_host, bucket, blob, f"ifGenerationNotMatch={blob.generation}"
    ) == 304
    assert status(
        storage_emulator_host,
        bucket,
        blob,
        f"ifMetagenerationNotMatch={blob.metageneration}",
    ) == 304
    # Same on the media read, which is where skipping the body actually pays.
    assert status(
        storage_emulator_host,
        bucket,
        blob,
        f"alt=media&ifGenerationNotMatch={blob.generation}",
    ) == 304


def test_match_that_does_not_hold_returns_412(storage_emulator_host, conditional_object):
    bucket, blob = conditional_object
    assert status(
        storage_emulator_host, bucket, blob, f"ifGenerationMatch={blob.generation + 1}"
    ) == 412
    assert status(
        storage_emulator_host,
        bucket,
        blob,
        f"ifMetagenerationMatch={blob.metageneration + 1}",
    ) == 412
    assert status(
        storage_emulator_host,
        bucket,
        blob,
        f"alt=media&ifGenerationMatch={blob.generation + 1}",
    ) == 412


def test_preconditions_that_hold_return_the_object(
    storage_emulator_host, conditional_object
):
    bucket, blob = conditional_object
    assert status(
        storage_emulator_host, bucket, blob, f"ifGenerationMatch={blob.generation}"
    ) == 200
    assert status(
        storage_emulator_host, bucket, blob, f"ifGenerationNotMatch={blob.generation + 1}"
    ) == 200
    assert status(
        storage_emulator_host, bucket, blob, f"alt=media&ifGenerationMatch={blob.generation}"
    ) == 200
