package io.floci.gcp.services.kafka;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.AclIdParser;
import io.floci.gcp.services.kafka.model.StoredAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaServiceTest {

    private KafkaService kafkaService;
    private static final String PROJECT = "test-proj";
    private static final String LOCATION = "us-central1";
    private static final String CLUSTER_ID = "test-cluster";

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.when(storageFactory.createGlobal(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(inv -> new InMemoryStorage<String, Object>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(config.services().kafka().mock()).thenReturn(true);
        ServiceRegistry serviceRegistry = new ServiceRegistry();
        RedpandaManager redpandaManager = Mockito.mock(RedpandaManager.class);

        kafkaService = new KafkaService(storageFactory, config, serviceRegistry, redpandaManager);
        kafkaService.createCluster(PROJECT, LOCATION, CLUSTER_ID, Map.of());
    }

    @Test
    void testAclIdParserAllGrammars() {
        AclIdParser.ParsedAclId c = AclIdParser.parse("cluster");
        assertEquals("CLUSTER", c.resourceType());
        assertEquals("kafka-cluster", c.resourceName());
        assertEquals("LITERAL", c.patternType());

        AclIdParser.ParsedAclId t = AclIdParser.parse("topic/my-topic");
        assertEquals("TOPIC", t.resourceType());
        assertEquals("my-topic", t.resourceName());
        assertEquals("LITERAL", t.patternType());

        AclIdParser.ParsedAclId cg = AclIdParser.parse("consumerGroup/my-group");
        assertEquals("CONSUMER_GROUP", cg.resourceType());
        assertEquals("my-group", cg.resourceName());
        assertEquals("LITERAL", cg.patternType());

        AclIdParser.ParsedAclId tx = AclIdParser.parse("transactionalId/tx-1");
        assertEquals("TRANSACTIONAL_ID", tx.resourceType());
        assertEquals("tx-1", tx.resourceName());
        assertEquals("LITERAL", tx.patternType());

        AclIdParser.ParsedAclId tp = AclIdParser.parse("topicPrefixed/my-prefix");
        assertEquals("TOPIC", tp.resourceType());
        assertEquals("my-prefix", tp.resourceName());
        assertEquals("PREFIXED", tp.patternType());

        AclIdParser.ParsedAclId cgp = AclIdParser.parse("consumerGroupPrefixed/my-prefix");
        assertEquals("CONSUMER_GROUP", cgp.resourceType());
        assertEquals("my-prefix", cgp.resourceName());
        assertEquals("PREFIXED", cgp.patternType());

        AclIdParser.ParsedAclId txp = AclIdParser.parse("transactionalIdPrefixed/my-prefix");
        assertEquals("TRANSACTIONAL_ID", txp.resourceType());
        assertEquals("my-prefix", txp.resourceName());
        assertEquals("PREFIXED", txp.patternType());

        AclIdParser.ParsedAclId at = AclIdParser.parse("allTopics");
        assertEquals("TOPIC", at.resourceType());
        assertEquals("*", at.resourceName());
        assertEquals("LITERAL", at.patternType());

        AclIdParser.ParsedAclId acg = AclIdParser.parse("allConsumerGroups");
        assertEquals("CONSUMER_GROUP", acg.resourceType());
        assertEquals("*", acg.resourceName());
        assertEquals("LITERAL", acg.patternType());

        AclIdParser.ParsedAclId atx = AclIdParser.parse("allTransactionalIds");
        assertEquals("TRANSACTIONAL_ID", atx.resourceType());
        assertEquals("*", atx.resourceName());
        assertEquals("LITERAL", atx.patternType());
    }

    @Test
    void testAclIdParserInvalidGrammar() {
        GcpException ex1 = assertThrows(GcpException.class, () -> AclIdParser.parse("invalid"));
        assertTrue(ex1.getMessage().contains("Invalid acl_id format"));

        GcpException ex2 = assertThrows(GcpException.class, () -> AclIdParser.parse("topic/"));
        assertTrue(ex2.getMessage().contains("missing resource name"));
    }

    @Test
    void testCreateAndGetAcl() {
        Map<String, Object> body = Map.of("aclEntries", List.of(
                Map.of("principal", "User:alice", "permissionType", "ALLOW", "operation", "READ", "host", "*")
        ));

        StoredAcl acl = kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/my-topic", body);
        assertTrue(acl.getName().contains("acls/topic/my-topic"));
        assertEquals("TOPIC", acl.getResourceType());
        assertEquals("my-topic", acl.getResourceName());
        assertEquals("LITERAL", acl.getPatternType());
        assertEquals(1, acl.getAclEntries().size());
        assertNotNull(acl.getEtag());

        StoredAcl retrieved = kafkaService.getAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/my-topic");
        assertEquals(acl.getName(), retrieved.getName());
    }

    @Test
    void testListAcls() {
        kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/t1", Map.of());
        kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/t2", Map.of());

        List<StoredAcl> acls = kafkaService.listAcls(PROJECT, LOCATION, CLUSTER_ID);
        assertEquals(2, acls.size());
    }

    @Test
    void testUpdateAclAndEtagValidation() {
        StoredAcl acl = kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/my-topic", Map.of());
        String origEtag = acl.getEtag();

        // Mismatched etag should throw aborted
        GcpException ex = assertThrows(GcpException.class, () -> kafkaService.updateAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/my-topic",
                Map.of("etag", "invalid-etag"), null));
        assertTrue(ex.getMessage().contains("Etag mismatch"));

        // Valid etag update succeeds
        StoredAcl updated = kafkaService.updateAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/my-topic",
                Map.of("etag", origEtag, "aclEntries", List.of(
                        Map.of("principal", "User:bob", "permissionType", "ALLOW", "operation", "WRITE", "host", "*")
                )), null);

        assertEquals(1, updated.getAclEntries().size());
        assertNotEquals(origEtag, updated.getEtag());
    }

    @Test
    void testAddAclEntryAndRemoveAclEntry() {
        // addAclEntry on non-existent ACL creates it
        Map<String, Object> addBody = Map.of("aclEntry", Map.of(
                "principal", "User:charlie", "permissionType", "ALLOW", "operation", "ALL", "host", "*"
        ));
        StoredAcl acl = kafkaService.addAclEntry(PROJECT, LOCATION, CLUSTER_ID, "cluster", addBody);
        assertEquals(1, acl.getAclEntries().size());

        // Add second entry
        Map<String, Object> addBody2 = Map.of("aclEntry", Map.of(
                "principal", "User:dave", "permissionType", "DENY", "operation", "READ", "host", "*"
        ));
        StoredAcl acl2 = kafkaService.addAclEntry(PROJECT, LOCATION, CLUSTER_ID, "cluster", addBody2);
        assertEquals(2, acl2.getAclEntries().size());

        // Remove first entry
        Map<String, Object> remResult1 = kafkaService.removeAclEntry(PROJECT, LOCATION, CLUSTER_ID, "cluster", addBody);
        assertEquals(false, remResult1.get("aclDeleted"));

        // Remove remaining entry -> ACL is deleted
        Map<String, Object> remResult2 = kafkaService.removeAclEntry(PROJECT, LOCATION, CLUSTER_ID, "cluster", addBody2);
        assertEquals(true, remResult2.get("aclDeleted"));

        assertThrows(GcpException.class, () -> kafkaService.getAcl(PROJECT, LOCATION, CLUSTER_ID, "cluster"));
    }

    @Test
    void testMax100EntriesLimit() {
        List<Map<String, String>> entries = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            entries.add(Map.of("principal", "User:user" + i, "permissionType", "ALLOW", "operation", "READ", "host", "*"));
        }

        GcpException ex = assertThrows(GcpException.class, () -> kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/large", Map.of("aclEntries", entries)));
        assertTrue(ex.getMessage().contains("cannot have more than 100 entries"));
    }

    @Test
    void testDeleteClusterCascadesAcls() {
        kafkaService.createAcl(PROJECT, LOCATION, CLUSTER_ID, "topic/t1", Map.of());
        kafkaService.deleteCluster(PROJECT, LOCATION, CLUSTER_ID);

        // Recreate cluster
        kafkaService.createCluster(PROJECT, LOCATION, CLUSTER_ID, Map.of());
        List<StoredAcl> acls = kafkaService.listAcls(PROJECT, LOCATION, CLUSTER_ID);
        assertTrue(acls.isEmpty());
    }
}
