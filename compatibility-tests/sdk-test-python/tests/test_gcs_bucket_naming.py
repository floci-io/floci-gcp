"""Bucket-name validation and the 409 reason a duplicate reports.

Bucket names are usually baked into configuration, so a name the emulator accepts and real
GCS refuses fails at deploy rather than during development. Every name below breaks one of the
documented rules at https://cloud.google.com/storage/docs/buckets#naming.
"""

import uuid

import pytest
from google.api_core import exceptions


# Names the SDK forwards, so the emulator is the thing that has to reject them.
SERVER_REJECTED = [
    "ab",                    # shorter than 3 characters
    "UpperCase",             # must be lowercase
    "has space",             # no spaces
    "double..dot",           # no consecutive dots
    "192.168.5.4",           # must not be formatted as an IP address
    "goog-reserved",         # the 'goog' prefix is reserved
    "contains-google-name",  # 'google' is reserved
    "a" * 64,                # over 63 characters without dot separation
]

# google-cloud-storage pre-flights only one rule client side (_helpers._validate_name checks
# that the first and last characters are alphanumeric), so these never reach the wire. Asserted
# separately rather than folded in, so the test says which layer does the rejecting.
CLIENT_REJECTED = [
    "-leading-hyphen",
    "trailing-hyphen-",
]


@pytest.mark.parametrize("name", SERVER_REJECTED)
def test_invalid_bucket_name_is_rejected_by_the_server(storage_client, name):
    with pytest.raises(exceptions.BadRequest):
        storage_client.create_bucket(storage_client.bucket(name))


@pytest.mark.parametrize("name", CLIENT_REJECTED)
def test_leading_or_trailing_non_alphanumeric_is_rejected_by_the_sdk(storage_client, name):
    with pytest.raises(ValueError):
        storage_client.bucket(name)


def test_dot_separated_name_may_exceed_63_characters(storage_client):
    # Above 63 characters a name is legal only when every dot-separated label is at most 63,
    # with the whole name at most 222.
    label = "a" * 60
    name = ".".join([label, label, label])
    assert 63 < len(name) <= 222

    bucket = storage_client.create_bucket(storage_client.bucket(name))
    try:
        assert bucket.name == name
    finally:
        bucket.delete(force=True)


def test_duplicate_bucket_reports_conflict(storage_client):
    name = f"duplicate-{uuid.uuid4().hex[:8]}"
    bucket = storage_client.create_bucket(storage_client.bucket(name))
    try:
        # GCS documents 'conflict' as the only reason it returns for this 409, and clients
        # branch on the reason rather than on the status code alone.
        with pytest.raises(exceptions.Conflict):
            storage_client.create_bucket(storage_client.bucket(name))
    finally:
        bucket.delete(force=True)
