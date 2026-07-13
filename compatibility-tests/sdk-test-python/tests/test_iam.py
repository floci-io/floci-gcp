"""IAM integration tests using plain HTTP (no emulator-aware SDK for IAM)."""

import json
import os
import urllib.request
import urllib.error


def _auth_headers(extra=None):
    token = (
        os.environ.get("GOOGLE_OAUTH_ACCESS_TOKEN")
        or os.environ.get("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN")
        or "fake-token-floci-gcp"
    )
    headers = {"Authorization": f"Bearer {token}"}
    if extra:
        headers.update(extra)
    return headers


def _post(url, body=None):
    data = json.dumps(body or {}).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers=_auth_headers({"Content-Type": "application/json"}),
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def _get(url):
    req = urllib.request.Request(url, headers=_auth_headers())
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def _delete(url):
    req = urllib.request.Request(url, headers=_auth_headers(), method="DELETE")
    try:
        urllib.request.urlopen(req)
    except urllib.error.HTTPError:
        pass


def test_create_service_account(endpoint, project_id, unique_name):
    account_id = f"py-sa-{unique_name}"
    email = f"{account_id}@{project_id}.iam.gserviceaccount.com"
    url = f"{endpoint}/v1/projects/{project_id}/serviceAccounts"

    resp = _post(url, {"accountId": account_id, "serviceAccount": {"displayName": "Python Test SA"}})

    assert resp["email"] == email
    assert resp["projectId"] == project_id
    assert account_id in resp["name"]

    _delete(f"{url}/{email}")


def test_get_service_account(endpoint, project_id, unique_name):
    account_id = f"py-sa-{unique_name}"
    email = f"{account_id}@{project_id}.iam.gserviceaccount.com"
    base = f"{endpoint}/v1/projects/{project_id}/serviceAccounts"

    _post(base, {"accountId": account_id, "serviceAccount": {"displayName": "Python Test SA"}})

    try:
        resp = _get(f"{base}/{email}")
        assert resp["email"] == email
        assert resp["displayName"] == "Python Test SA"
    finally:
        _delete(f"{base}/{email}")


def test_list_service_accounts(endpoint, project_id, unique_name):
    account_id = f"py-sa-{unique_name}"
    email = f"{account_id}@{project_id}.iam.gserviceaccount.com"
    base = f"{endpoint}/v1/projects/{project_id}/serviceAccounts"

    _post(base, {"accountId": account_id, "serviceAccount": {"displayName": "Python Test SA"}})

    try:
        resp = _get(base)
        emails = [a["email"] for a in resp.get("accounts", [])]
        assert email in emails
    finally:
        _delete(f"{base}/{email}")


def test_iam_policy(endpoint, project_id, unique_name):
    account_id = f"py-sa-{unique_name}"
    email = f"{account_id}@{project_id}.iam.gserviceaccount.com"
    base = f"{endpoint}/v1/projects/{project_id}/serviceAccounts"

    _post(base, {"accountId": account_id, "serviceAccount": {}})

    try:
        # getIamPolicy — empty
        policy = _post(f"{base}/{email}:getIamPolicy", {})
        assert policy["version"] == 1
        assert policy["bindings"] == []

        # setIamPolicy
        binding = {"role": "roles/iam.serviceAccountUser", "members": ["user:alice@example.com"]}
        updated = _post(f"{base}/{email}:setIamPolicy",
                        {"policy": {"version": 1, "bindings": [binding]}})
        assert len(updated["bindings"]) == 1
        assert updated["bindings"][0]["role"] == "roles/iam.serviceAccountUser"
    finally:
        _delete(f"{base}/{email}")


def test_test_iam_permissions(endpoint, project_id, unique_name):
    account_id = f"py-sa-{unique_name}"
    email = f"{account_id}@{project_id}.iam.gserviceaccount.com"
    base = f"{endpoint}/v1/projects/{project_id}/serviceAccounts"

    _post(base, {"accountId": account_id, "serviceAccount": {}})

    try:
        permissions = ["iam.serviceAccounts.get", "iam.serviceAccounts.list"]
        resp = _post(f"{base}/{email}:testIamPermissions", {"permissions": permissions})
        assert set(resp["permissions"]) == set(permissions)
    finally:
        _delete(f"{base}/{email}")
