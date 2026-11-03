"""projects.serviceAccount.

Clients call this before wiring Pub/Sub notifications, to learn which principal needs publish
rights on the topic. It used to 404 even though notificationConfigs itself worked, which broke
that setup path.
"""

import json
import urllib.request


def _project_number(endpoint, project_id):
    with urllib.request.urlopen(f"{endpoint}/v1/projects/{project_id}") as response:
        return json.load(response)["projectNumber"]


def test_project_exposes_its_storage_service_account(storage_client, endpoint, project_id):
    email = storage_client.get_service_account_email()

    # GCP names the Cloud Storage agent by project number, not project ID.
    number = _project_number(endpoint, project_id)
    assert email == f"service-{number}@gs-project-accounts.iam.gserviceaccount.com"


def test_service_account_is_stable_across_calls(storage_client):
    # Callers store this address in IAM bindings, so it must not move between calls.
    assert (
        storage_client.get_service_account_email()
        == storage_client.get_service_account_email()
    )
