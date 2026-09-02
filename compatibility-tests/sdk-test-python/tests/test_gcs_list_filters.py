"""objects.list filters beyond prefix: endOffset, matchGlob and includeTrailingDelimiter.

The glob cases are the ones worth pinning: ``*`` stays inside one path segment while ``**``
crosses them, a distinction a naive translation to ``.*`` collapses.
"""

import pytest


OBJECTS = [
    "logs/",
    "logs/app.log",
    "logs/app.txt",
    "logs/2026/01/app.log",
    "logs/2026/02/app.log",
    "metrics/cpu.log",
]


@pytest.fixture
def filter_bucket(storage_client, unique_name):
    bucket = storage_client.create_bucket(storage_client.bucket(f"filters-{unique_name}"))
    for name in OBJECTS:
        bucket.blob(name).upload_from_string(b"")
    yield bucket
    bucket.delete(force=True)


def names(storage_client, bucket, **kwargs):
    return [blob.name for blob in storage_client.list_blobs(bucket, **kwargs)]


def test_end_offset_is_exclusive(storage_client, filter_bucket):
    listed = names(storage_client, filter_bucket, end_offset="logs/app.log")
    assert "logs/2026/01/app.log" in listed
    assert "logs/app.log" not in listed
    assert "metrics/cpu.log" not in listed


def test_start_and_end_offset_bracket_the_range(storage_client, filter_bucket):
    listed = names(
        storage_client, filter_bucket, start_offset="logs/app.log", end_offset="logs/app.txt"
    )
    assert listed == ["logs/app.log"]


def test_match_glob_keeps_single_star_within_one_segment(storage_client, filter_bucket):
    assert names(storage_client, filter_bucket, match_glob="logs/*.log") == ["logs/app.log"]

    assert sorted(names(storage_client, filter_bucket, match_glob="logs/**.log")) == sorted(
        ["logs/app.log", "logs/2026/01/app.log", "logs/2026/02/app.log"]
    )

    crossing = names(storage_client, filter_bucket, match_glob="**/*.log")
    assert "logs/2026/01/app.log" in crossing
    assert "metrics/cpu.log" in crossing


def test_include_trailing_delimiter_adds_the_placeholder_to_items(
    storage_client, filter_bucket
):
    # Without the flag "logs/" only rolls up into prefixes and its own metadata is invisible.
    without_flag = names(storage_client, filter_bucket, delimiter="/")
    assert "logs/" not in without_flag

    with_flag = names(
        storage_client, filter_bucket, delimiter="/", include_trailing_delimiter=True
    )
    assert "logs/" in with_flag


def test_delimiter_rolls_directories_up_into_prefixes(storage_client, filter_bucket):
    iterator = storage_client.list_blobs(filter_bucket, delimiter="/")
    list(iterator)  # prefixes are only populated once the pages have been consumed
    assert iterator.prefixes == {"logs/", "metrics/"}
