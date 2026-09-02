"""projects.serviceAccount.

Clients call this before wiring Pub/Sub notifications, to learn which principal needs publish
rights on the topic. It used to 404 even though notificationConfigs itself worked, which broke
that setup path.
"""


def test_project_exposes_its_storage_service_account(storage_client, project_id):
    email = storage_client.get_service_account_email()

    assert project_id in email
    assert email.endswith("gs-project-accounts.iam.gserviceaccount.com")


def test_service_account_is_stable_across_calls(storage_client):
    # Callers store this address in IAM bindings, so it must not move between calls.
    assert (
        storage_client.get_service_account_email()
        == storage_client.get_service_account_email()
    )
