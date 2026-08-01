"""Pub/Sub integration tests using google-cloud-pubsub."""

import time
import pytest


def test_create_topic(pubsub_publisher, project_id, unique_name):
    topic_path = pubsub_publisher.topic_path(project_id, f"test-topic-{unique_name}")
    topic = pubsub_publisher.create_topic(request={"name": topic_path})
    assert topic.name == topic_path

    # Cleanup
    pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_create_subscription(pubsub_publisher, pubsub_subscriber, project_id, unique_name):
    topic_id = f"test-topic-{unique_name}"
    sub_id = f"test-sub-{unique_name}"
    topic_path = pubsub_publisher.topic_path(project_id, topic_id)
    sub_path = pubsub_subscriber.subscription_path(project_id, sub_id)

    pubsub_publisher.create_topic(request={"name": topic_path})
    sub = pubsub_subscriber.create_subscription(request={"name": sub_path, "topic": topic_path})

    assert sub.name == sub_path
    assert sub.topic == topic_path

    # Cleanup
    pubsub_subscriber.delete_subscription(request={"subscription": sub_path})
    pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_publish_and_pull_messages(pubsub_publisher, pubsub_subscriber, project_id, unique_name):
    topic_id = f"test-topic-{unique_name}"
    sub_id = f"test-sub-{unique_name}"
    topic_path = pubsub_publisher.topic_path(project_id, topic_id)
    sub_path = pubsub_subscriber.subscription_path(project_id, sub_id)

    pubsub_publisher.create_topic(request={"name": topic_path})
    pubsub_subscriber.create_subscription(request={"name": sub_path, "topic": topic_path})

    try:
        # Publish messages
        future1 = pubsub_publisher.publish(topic_path, b"Hello, GCP Pub/Sub from Python!")
        future2 = pubsub_publisher.publish(topic_path, b"Second message", source="python-test")
        id1 = future1.result(timeout=10)
        id2 = future2.result(timeout=10)

        assert id1
        assert id2
        assert id1 != id2

        # Pull messages
        time.sleep(0.2)
        response = pubsub_subscriber.pull(
            request={"subscription": sub_path, "max_messages": 10}
        )

        assert len(response.received_messages) >= 2

        bodies = [m.message.data.decode() for m in response.received_messages]
        assert "Hello, GCP Pub/Sub from Python!" in bodies
        assert "Second message" in bodies

        # Acknowledge
        ack_ids = [m.ack_id for m in response.received_messages]
        pubsub_subscriber.acknowledge(request={"subscription": sub_path, "ack_ids": ack_ids})
    finally:
        pubsub_subscriber.delete_subscription(request={"subscription": sub_path})
        pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_subscription_filter_only_delivers_matching_messages(
    pubsub_publisher, pubsub_subscriber, project_id, unique_name
):
    topic_path = pubsub_publisher.topic_path(project_id, f"filter-topic-{unique_name}")
    filtered_path = pubsub_subscriber.subscription_path(project_id, f"filtered-sub-{unique_name}")
    unfiltered_path = pubsub_subscriber.subscription_path(project_id, f"unfiltered-sub-{unique_name}")

    pubsub_publisher.create_topic(request={"name": topic_path})
    sub = pubsub_subscriber.create_subscription(
        request={
            "name": filtered_path,
            "topic": topic_path,
            "filter": 'attributes.event_type = "ocr-invoice"',
        }
    )
    pubsub_subscriber.create_subscription(
        request={"name": unfiltered_path, "topic": topic_path}
    )

    try:
        assert sub.filter == 'attributes.event_type = "ocr-invoice"'

        pubsub_publisher.publish(topic_path, b"excluded", event_type="portal.upload").result(timeout=10)
        pubsub_publisher.publish(topic_path, b"included", event_type="ocr-invoice").result(timeout=10)

        time.sleep(0.2)
        filtered = pubsub_subscriber.pull(
            request={"subscription": filtered_path, "max_messages": 10}
        )
        assert len(filtered.received_messages) == 1
        assert filtered.received_messages[0].message.data == b"included"

        unfiltered = pubsub_subscriber.pull(
            request={"subscription": unfiltered_path, "max_messages": 10}
        )
        assert len(unfiltered.received_messages) == 2
    finally:
        pubsub_subscriber.delete_subscription(request={"subscription": filtered_path})
        pubsub_subscriber.delete_subscription(request={"subscription": unfiltered_path})
        pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_has_prefix_filter_only_delivers_matching_messages(
    pubsub_publisher, pubsub_subscriber, project_id, unique_name
):
    topic_path = pubsub_publisher.topic_path(project_id, f"prefix-topic-{unique_name}")
    sub_path = pubsub_subscriber.subscription_path(project_id, f"prefix-sub-{unique_name}")

    pubsub_publisher.create_topic(request={"name": topic_path})
    pubsub_subscriber.create_subscription(
        request={
            "name": sub_path,
            "topic": topic_path,
            "filter": 'hasPrefix(attributes.event_type, "portal.")',
        }
    )

    try:
        pubsub_publisher.publish(topic_path, b"excluded", event_type="ocr-invoice").result(timeout=10)
        pubsub_publisher.publish(topic_path, b"included", event_type="portal.upload").result(timeout=10)

        time.sleep(0.2)
        response = pubsub_subscriber.pull(
            request={"subscription": sub_path, "max_messages": 10}
        )
        assert len(response.received_messages) == 1
        assert response.received_messages[0].message.data == b"included"
    finally:
        pubsub_subscriber.delete_subscription(request={"subscription": sub_path})
        pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_unparseable_filter_is_rejected(
    pubsub_publisher, pubsub_subscriber, project_id, unique_name
):
    from google.api_core.exceptions import InvalidArgument

    topic_path = pubsub_publisher.topic_path(project_id, f"bad-filter-topic-{unique_name}")
    sub_path = pubsub_subscriber.subscription_path(project_id, f"bad-filter-sub-{unique_name}")

    pubsub_publisher.create_topic(request={"name": topic_path})

    try:
        with pytest.raises(InvalidArgument):
            pubsub_subscriber.create_subscription(
                request={
                    "name": sub_path,
                    "topic": topic_path,
                    "filter": "this is not a filter (((",
                }
            )
    finally:
        pubsub_publisher.delete_topic(request={"topic": topic_path})


def test_list_topics(pubsub_publisher, project_id, unique_name):
    topic_id = f"test-topic-{unique_name}"
    topic_path = pubsub_publisher.topic_path(project_id, topic_id)
    pubsub_publisher.create_topic(request={"name": topic_path})

    try:
        topics = [t.name for t in pubsub_publisher.list_topics(request={"project": f"projects/{project_id}"})]
        assert topic_path in topics
    finally:
        pubsub_publisher.delete_topic(request={"topic": topic_path})
