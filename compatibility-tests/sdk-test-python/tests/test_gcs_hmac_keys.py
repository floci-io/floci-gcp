"""HMAC keys: the credentials S3-compatible clients present to GCS.

boto3, the AWS SDKs and gsutil in interop mode all authenticate this way. The lifecycle
matters as much as the payload: a key must be moved to INACTIVE before it can be deleted, and
code that does not handle that 400 gets stuck.
"""

import pytest
from google.api_core import exceptions


@pytest.fixture
def service_account_email(project_id):
    return f"hmac-python@{project_id}.iam.gserviceaccount.com"


def test_key_lifecycle_requires_deactivation_before_deletion(
    storage_client, service_account_email
):
    metadata, secret = storage_client.create_hmac_key(
        service_account_email=service_account_email
    )

    # The secret is returned once, on create, and never again.
    assert secret
    assert metadata.state == "ACTIVE"
    assert metadata.access_id
    assert metadata.service_account_email == service_account_email

    fetched = storage_client.get_hmac_key_metadata(metadata.access_id)
    assert fetched.state == "ACTIVE"

    access_ids = [key.access_id for key in storage_client.list_hmac_keys()]
    assert metadata.access_id in access_ids

    # Deleting an ACTIVE key is refused, which is the step callers most often miss.
    with pytest.raises(exceptions.BadRequest):
        fetched.delete()

    fetched.state = "INACTIVE"
    fetched.update()
    assert storage_client.get_hmac_key_metadata(metadata.access_id).state == "INACTIVE"

    fetched.delete()

    access_ids = [key.access_id for key in storage_client.list_hmac_keys()]
    assert metadata.access_id not in access_ids
    with pytest.raises(exceptions.NotFound):
        storage_client.get_hmac_key_metadata(metadata.access_id)


def test_deactivated_key_can_be_reactivated(storage_client, service_account_email):
    metadata, _ = storage_client.create_hmac_key(
        service_account_email=service_account_email
    )
    try:
        metadata.state = "INACTIVE"
        metadata.update()
        assert metadata.state == "INACTIVE"

        metadata.state = "ACTIVE"
        metadata.update()
        assert storage_client.get_hmac_key_metadata(metadata.access_id).state == "ACTIVE"
    finally:
        metadata.state = "INACTIVE"
        metadata.update()
        metadata.delete()
