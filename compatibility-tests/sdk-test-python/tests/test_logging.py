"""Cloud Logging integration tests using google-cloud-logging (low-level v2 client)."""

from google.logging.type import log_severity_pb2


def _log_name(project_id, log_id):
    return f"projects/{project_id}/logs/{log_id}"


def _entry(log_name, severity, text):
    return {
        "log_name": log_name,
        "resource": {"type": "global"},
        "severity": severity,
        "text_payload": text,
    }


def test_write_then_list_round_trip(logging_client, project_id, unique_name):
    log_name = _log_name(project_id, f"test-log-{unique_name}")
    parent = f"projects/{project_id}"

    logging_client.write_log_entries(
        request={
            "entries": [
                _entry(log_name, "INFO", "info-message"),
                _entry(log_name, "ERROR", "error-message"),
            ]
        }
    )

    try:
        entries = list(
            logging_client.list_log_entries(
                request={"resource_names": [parent], "filter": f'logName="{log_name}"'}
            )
        )
        assert len(entries) == 2
        assert all(e.resource.type == "global" for e in entries)
        payloads = {e.text_payload for e in entries}
        assert payloads == {"info-message", "error-message"}
    finally:
        logging_client.delete_log(request={"log_name": log_name})


def test_list_with_severity_filter(logging_client, project_id, unique_name):
    log_name = _log_name(project_id, f"test-log-{unique_name}")
    parent = f"projects/{project_id}"

    logging_client.write_log_entries(
        request={
            "entries": [
                _entry(log_name, "INFO", "info-message"),
                _entry(log_name, "ERROR", "error-message"),
            ]
        }
    )

    try:
        entries = list(
            logging_client.list_log_entries(
                request={
                    "resource_names": [parent],
                    "filter": f'logName="{log_name}" AND severity>=WARNING',
                }
            )
        )
        assert len(entries) == 1
        assert entries[0].text_payload == "error-message"
        assert entries[0].severity == log_severity_pb2.LogSeverity.ERROR
    finally:
        logging_client.delete_log(request={"log_name": log_name})


def test_list_logs_and_delete(logging_client, project_id, unique_name):
    log_name = _log_name(project_id, f"test-log-{unique_name}")
    parent = f"projects/{project_id}"

    logging_client.write_log_entries(request={"entries": [_entry(log_name, "INFO", "x")]})

    log_names = list(logging_client.list_logs(request={"parent": parent}))
    assert log_name in log_names

    logging_client.delete_log(request={"log_name": log_name})
    log_names = list(logging_client.list_logs(request={"parent": parent}))
    assert log_name not in log_names
