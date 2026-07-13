"""Managed Kafka integration tests using plain HTTP."""

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


def _patch(url, body=None):
    data = json.dumps(body or {}).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers=_auth_headers({"Content-Type": "application/json"}),
        method="PATCH",
    )
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def _delete(url):
    req = urllib.request.Request(url, headers=_auth_headers(), method="DELETE")
    try:
        urllib.request.urlopen(req)
    except urllib.error.HTTPError:
        pass


def _base(endpoint, project_id):
    return f"{endpoint}/v1/projects/{project_id}/locations/us-central1"


def test_create_cluster(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    base = _base(endpoint, project_id)
    url = f"{base}/clusters?clusterId={cluster_id}"

    resp = _post(url, {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": [
            {"subnet": f"projects/{project_id}/regions/us-central1/subnetworks/default"}
        ]}}
    })

    assert resp["done"] is True
    assert cluster_id in resp["response"]["name"]
    assert resp["response"]["state"] == "ACTIVE"

    _delete(f"{base}/clusters/{cluster_id}")


def test_get_cluster(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        resp = _get(f"{base}/clusters/{cluster_id}")
        assert cluster_id in resp["name"]
        assert resp["state"] == "ACTIVE"
        assert resp["bootstrapAddress"]
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_list_clusters(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        resp = _get(f"{base}/clusters")
        names = [c["name"] for c in resp.get("clusters", [])]
        assert any(cluster_id in n for n in names)
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_create_and_list_topics(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    topic_id = f"py-topic-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        topic = _post(f"{base}/clusters/{cluster_id}/topics?topicId={topic_id}",
                      {"partitionCount": 3, "replicationFactor": 1})
        assert topic_id in topic["name"]
        assert topic["partitionCount"] == 3

        topics_resp = _get(f"{base}/clusters/{cluster_id}/topics")
        names = [t["name"] for t in topics_resp.get("topics", [])]
        assert any(topic_id in n for n in names)
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_update_cluster(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        resp = _patch(f"{base}/clusters/{cluster_id}",
                      {"capacityConfig": {"vcpuCount": 6, "memoryBytes": 6442450944}})
        assert resp["done"] is True
        assert resp["response"]["vcpuCount"] == 6
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_update_topic(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    topic_id = f"py-topic-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        _post(f"{base}/clusters/{cluster_id}/topics?topicId={topic_id}",
              {"partitionCount": 3, "replicationFactor": 1})

        resp = _patch(f"{base}/clusters/{cluster_id}/topics/{topic_id}", {"partitionCount": 6})
        assert resp["partitionCount"] == 6
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_list_consumer_groups(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        resp = _get(f"{base}/clusters/{cluster_id}/consumerGroups")
        assert "consumerGroups" in resp
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_get_topic(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    topic_id = f"py-topic-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        _post(f"{base}/clusters/{cluster_id}/topics?topicId={topic_id}",
              {"partitionCount": 3, "replicationFactor": 1})

        resp = _get(f"{base}/clusters/{cluster_id}/topics/{topic_id}")
        assert topic_id in resp["name"]
        assert resp["partitionCount"] == 3
    finally:
        _delete(f"{base}/clusters/{cluster_id}")


def test_delete_topic(endpoint, project_id, unique_name):
    cluster_id = f"py-cluster-{unique_name}"
    topic_id = f"py-topic-{unique_name}"
    base = _base(endpoint, project_id)

    _post(f"{base}/clusters?clusterId={cluster_id}", {
        "capacityConfig": {"vcpuCount": 3, "memoryBytes": 3221225472},
        "gcpConfig": {"accessConfig": {"networkConfigs": []}}
    })

    try:
        _post(f"{base}/clusters/{cluster_id}/topics?topicId={topic_id}",
              {"partitionCount": 3, "replicationFactor": 1})

        _delete(f"{base}/clusters/{cluster_id}/topics/{topic_id}")

        topics_resp = _get(f"{base}/clusters/{cluster_id}/topics")
        names = [t["name"] for t in topics_resp.get("topics", [])]
        assert not any(topic_id in n for n in names)
    finally:
        _delete(f"{base}/clusters/{cluster_id}")
