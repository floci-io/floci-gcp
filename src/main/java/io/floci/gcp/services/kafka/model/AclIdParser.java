package io.floci.gcp.services.kafka.model;

import io.floci.gcp.core.common.GcpException;

public class AclIdParser {

    public record ParsedAclId(String aclId, String resourceType, String resourceName, String patternType) {}

    public static ParsedAclId parse(String aclId) {
        if (aclId == null || aclId.isBlank()) {
            throw GcpException.invalidArgument("acl_id must not be empty");
        }

        if ("cluster".equals(aclId)) {
            return new ParsedAclId(aclId, "CLUSTER", "kafka-cluster", "LITERAL");
        }
        if ("allTopics".equals(aclId)) {
            return new ParsedAclId(aclId, "TOPIC", "*", "LITERAL");
        }
        if ("allConsumerGroups".equals(aclId)) {
            return new ParsedAclId(aclId, "CONSUMER_GROUP", "*", "LITERAL");
        }
        if ("allTransactionalIds".equals(aclId)) {
            return new ParsedAclId(aclId, "TRANSACTIONAL_ID", "*", "LITERAL");
        }

        if (aclId.startsWith("topic/")) {
            String name = aclId.substring("topic/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "TOPIC", name, "LITERAL");
        }
        if (aclId.startsWith("consumerGroup/")) {
            String name = aclId.substring("consumerGroup/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "CONSUMER_GROUP", name, "LITERAL");
        }
        if (aclId.startsWith("transactionalId/")) {
            String name = aclId.substring("transactionalId/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "TRANSACTIONAL_ID", name, "LITERAL");
        }
        if (aclId.startsWith("topicPrefixed/")) {
            String name = aclId.substring("topicPrefixed/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "TOPIC", name, "PREFIXED");
        }
        if (aclId.startsWith("consumerGroupPrefixed/")) {
            String name = aclId.substring("consumerGroupPrefixed/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "CONSUMER_GROUP", name, "PREFIXED");
        }
        if (aclId.startsWith("transactionalIdPrefixed/")) {
            String name = aclId.substring("transactionalIdPrefixed/".length());
            validateName(aclId, name);
            return new ParsedAclId(aclId, "TRANSACTIONAL_ID", name, "PREFIXED");
        }

        throw GcpException.invalidArgument("Invalid acl_id format: " + aclId);
    }

    private static void validateName(String aclId, String name) {
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("Invalid acl_id format (missing resource name): " + aclId);
        }
    }
}
