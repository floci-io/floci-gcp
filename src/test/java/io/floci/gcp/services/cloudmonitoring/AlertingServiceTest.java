package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.ComparisonType;
import com.google.monitoring.v3.NotificationChannel;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertingServiceTest {
    private static final String PARENT = "projects/test-project";

    private NotificationChannelService channels;
    private AlertPolicyService policies;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
        channels = new NotificationChannelService(new InMemoryStorage<>(), null, clock);
        policies = new AlertPolicyService(new InMemoryStorage<>(), channels, null, null, clock);
    }

    // ── Notification channels ────────────────────────────────────────────

    @Test
    void createdChannelsGetAServerNameARecordAndAreEnabledByDefault() {
        NotificationChannel email = channels.create(PARENT, email("ops@example.com"));
        assertTrue(email.getName().matches(PARENT + "/notificationChannels/[0-9]+"), email.getName());
        assertTrue(email.getEnabled().getValue());
        assertTrue(email.hasCreationRecord());
        assertEquals(NotificationChannel.VerificationStatus.VERIFICATION_STATUS_UNSPECIFIED, email.getVerificationStatus());
        assertEquals(email, channels.get(email.getName()));

        NotificationChannel sms = channels.create(PARENT, NotificationChannel.newBuilder().setType("sms").build());
        assertEquals(NotificationChannel.VerificationStatus.UNVERIFIED, sms.getVerificationStatus());
        assertFalse(channels.create(PARENT, email("off@example.com").toBuilder()
                .setEnabled(BoolValue.of(false)).build()).getEnabled().getValue());
    }

    @Test
    void channelsAreValidatedAgainstTheirDescriptor() {
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.create(PARENT,
                NotificationChannel.newBuilder().setType("carrier_pigeon").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.create(PARENT,
                NotificationChannel.newBuilder().setType("email").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.create(PARENT,
                email("a@example.com").toBuilder().putLabels("url", "http://x").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.create(PARENT,
                email("a@example.com").toBuilder()
                        .setVerificationStatus(NotificationChannel.VerificationStatus.VERIFIED).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.create(PARENT,
                email("a@example.com").toBuilder().putUserLabels("Team", "ops").build())));
        assertEquals("pagerduty", channels.create(PARENT, NotificationChannel.newBuilder().setType("pagerduty")
                .putLabels("service_key", "key").build()).getType());
    }

    @Test
    void updatesMaskOrReplaceButKeepIdentityRecordsAndVerification() {
        NotificationChannel created = channels.create(PARENT, email("ops@example.com"));

        NotificationChannel masked = channels.update(created.toBuilder().setDisplayName("Ops")
                .putLabels("email_address", "ignored@example.com").build(),
                FieldMask.newBuilder().addPaths("display_name").build());
        assertEquals("Ops", masked.getDisplayName());
        assertEquals("ops@example.com", masked.getLabelsOrThrow("email_address"));
        assertEquals(1, masked.getMutationRecordsCount());

        NotificationChannel replaced = channels.update(NotificationChannel.newBuilder().setName(created.getName())
                .setType("email").putLabels("email_address", "new@example.com").build(), FieldMask.getDefaultInstance());
        assertEquals("", replaced.getDisplayName());
        assertEquals(created.getCreationRecord(), replaced.getCreationRecord());
        assertEquals(2, replaced.getMutationRecordsCount());
        assertTrue(replaced.getEnabled().getValue());

        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.update(replaced.toBuilder()
                .setVerificationStatus(NotificationChannel.VerificationStatus.VERIFIED).build(), FieldMask.getDefaultInstance())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.update(replaced,
                FieldMask.newBuilder().addPaths("verification_status").build())));
    }

    @Test
    void listFiltersOnTypeLabelsAndUserLabelsAndReportsTheTotal() {
        channels.create(PARENT, email("a@example.com").toBuilder().setDisplayName("A").putUserLabels("team", "ops").build());
        channels.create(PARENT, email("b@example.com").toBuilder().setDisplayName("B").build());
        channels.create(PARENT, NotificationChannel.newBuilder().setType("webhook_tokenauth")
                .putLabels("url", "http://hook").build());

        assertEquals(3, channels.list(PARENT, null, 0, null).getTotalSize());
        assertEquals(List.of("A"), channels.list(PARENT, "type=\"email\" AND user_labels.team=\"ops\"", 0, null)
                .getNotificationChannelsList().stream().map(NotificationChannel::getDisplayName).toList());
        assertEquals(1, channels.list(PARENT, "labels.email_address=\"b@example.com\"", 0, null).getTotalSize());
        assertEquals(0, channels.list("projects/other", null, 0, null).getTotalSize());
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.list(PARENT, "metric.type=\"x\"", 0, null)));
    }

    @Test
    void descriptorsListTheDocumentedTypes() {
        var descriptors = channels.listDescriptors(PARENT, 0, null).getChannelDescriptorsList();
        assertTrue(descriptors.stream().anyMatch(d -> d.getType().equals("pubsub")
                && d.getLabels(0).getKey().equals("topic")));
        assertEquals(10, descriptors.size());
        assertEquals(PARENT + "/notificationChannelDescriptors/email",
                channels.getDescriptor(PARENT + "/notificationChannelDescriptors/email").getName());
        assertEquals(Status.Code.NOT_FOUND,
                code(() -> channels.getDescriptor(PARENT + "/notificationChannelDescriptors/telegraph")));
    }

    @Test
    void verificationUsesTheSentCodeAndCopyCodesOnlyComeFromVerifiedChannels() {
        NotificationChannel sms = channels.create(PARENT, NotificationChannel.newBuilder().setType("sms").build());
        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> channels.getVerificationCode(sms.getName(), null)));

        channels.sendVerificationCode(sms.getName());
        String sent = channels.sentCode(sms.getName());
        assertTrue(sent.matches("G-[0-9]{6}"));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.verify(sms.getName(), "G-000000x")));
        NotificationChannel verified = channels.verify(sms.getName(), sent);
        assertEquals(NotificationChannel.VerificationStatus.VERIFIED, verified.getVerificationStatus());

        String copy = channels.getVerificationCode(sms.getName(), null).getCode();
        NotificationChannel twin = channels.create("projects/other", NotificationChannel.newBuilder().setType("sms").build());
        assertEquals(NotificationChannel.VerificationStatus.VERIFIED, channels.verify(twin.getName(), copy).getVerificationStatus());
    }

    @Test
    void aMaskedUpdateCanClearTheDisplayNameAndDescription() {
        NotificationChannel created = channels.create(PARENT, email("ops@example.com").toBuilder()
                .setDisplayName("Ops").setDescription("On-call").build());

        NotificationChannel cleared = channels.update(NotificationChannel.newBuilder().setName(created.getName()).build(),
                FieldMask.newBuilder().addPaths("display_name").addPaths("description").build());

        assertEquals("", cleared.getDisplayName());
        assertEquals("", cleared.getDescription());
        assertEquals("ops@example.com", cleared.getLabelsOrThrow("email_address"));
    }

    @Test
    void changingTheDestinationLabelsResetsVerificationAndDropsTheSentCode() {
        FieldMask labels = FieldMask.newBuilder().addPaths("labels").build();
        NotificationChannel sms = channels.create(PARENT, sms("+15550000001"));
        channels.sendVerificationCode(sms.getName());
        channels.verify(sms.getName(), channels.sentCode(sms.getName()));
        NotificationChannel renamed = channels.update(channels.get(sms.getName()).toBuilder().setDisplayName("Phone").build(),
                FieldMask.newBuilder().addPaths("display_name").build());
        assertEquals(NotificationChannel.VerificationStatus.VERIFIED, renamed.getVerificationStatus());

        NotificationChannel moved = channels.update(sms("+15550000002").toBuilder().setName(sms.getName()).build(), labels);
        assertEquals(NotificationChannel.VerificationStatus.UNVERIFIED, moved.getVerificationStatus());
        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> channels.getVerificationCode(sms.getName(), null)));

        NotificationChannel pending = channels.create(PARENT, sms("+15550000003"));
        channels.sendVerificationCode(pending.getName());
        String sent = channels.sentCode(pending.getName());
        channels.update(sms("+15550000004").toBuilder().setName(pending.getName()).build(), labels);
        assertNull(channels.sentCode(pending.getName()));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> channels.verify(pending.getName(), sent)));
    }

    // ── Alert policies ───────────────────────────────────────────────────

    @Test
    void createdPoliciesGetNamesForThemAndTheirConditionsAndServerDefaults() {
        AlertPolicy created = policies.create(PARENT, policy("CPU", threshold("high")).toBuilder()
                .setDocumentation(AlertPolicy.Documentation.newBuilder().setContent("Page someone")).build());

        assertTrue(created.getName().matches(PARENT + "/alertPolicies/[0-9]+"), created.getName());
        assertTrue(created.getConditions(0).getName().startsWith(created.getName() + "/conditions/"));
        assertTrue(created.getEnabled().getValue());
        assertEquals("text/markdown", created.getDocumentation().getMimeType());
        assertEquals(created.getCreationRecord(), created.getMutationRecord());
        assertEquals(created, policies.get(created.getName()));
    }

    @Test
    void policiesAreValidated() {
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT,
                AlertPolicy.newBuilder().setDisplayName("none").build())));
        AlertPolicy.Builder seven = policy("seven", threshold("1")).toBuilder().setCombiner(AlertPolicy.ConditionCombinerType.OR);
        for (int i = 2; i <= 7; i++) {
            seven.addConditions(threshold(String.valueOf(i)));
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, seven.build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, policy("unset",
                AlertPolicy.Condition.newBuilder().setDisplayName("unset").build()))));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT,
                policy("no combiner", threshold("a")).toBuilder().addConditions(threshold("b")).build())));
        AlertPolicy.Condition logMatch = AlertPolicy.Condition.newBuilder().setDisplayName("logs")
                .setConditionMatchedLog(AlertPolicy.Condition.LogMatch.newBuilder().setFilter("severity>=ERROR")).build();
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, policy("logs", logMatch))));
        assertNotNull(policies.create(PARENT, policy("logs", logMatch).toBuilder()
                .setCombiner(AlertPolicy.ConditionCombinerType.OR).build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, policy("short close",
                threshold("a")).toBuilder().setAlertStrategy(AlertPolicy.AlertStrategy.newBuilder()
                        .setAutoClose(Duration.newBuilder().setSeconds(60))).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, policy("html", threshold("a"))
                .toBuilder().setDocumentation(AlertPolicy.Documentation.newBuilder().setMimeType("text/html")).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.create(PARENT, policy("absent",
                AlertPolicy.Condition.newBuilder().setDisplayName("gone").setConditionAbsent(
                        AlertPolicy.Condition.MetricAbsence.newBuilder().setFilter("metric.type=\"x\"")
                                .setDuration(Duration.newBuilder().setSeconds(60))).build()))));
    }

    @Test
    void replacingKeepsNamedConditionsAddsUnnamedOnesAndDropsTheRest() {
        AlertPolicy created = policies.create(PARENT, policy("two", threshold("a")).toBuilder()
                .addConditions(threshold("b")).setCombiner(AlertPolicy.ConditionCombinerType.OR).build());
        AlertPolicy.Condition kept = created.getConditions(0);

        AlertPolicy replaced = policies.update(created.toBuilder().clearConditions()
                .addConditions(kept).addConditions(threshold("c")).build(), FieldMask.getDefaultInstance());
        assertEquals(2, replaced.getConditionsCount());
        assertEquals(kept.getName(), replaced.getConditions(0).getName());
        assertNotEquals(created.getConditions(1).getName(), replaced.getConditions(1).getName());
        assertEquals(created.getCreationRecord(), replaced.getCreationRecord());

        AlertPolicy masked = policies.update(AlertPolicy.newBuilder().setName(created.getName())
                .setDisplayName("renamed").build(), FieldMask.newBuilder().addPaths("display_name").build());
        assertEquals("renamed", masked.getDisplayName());
        assertEquals(2, masked.getConditionsCount());
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> policies.update(masked,
                FieldMask.newBuilder().addPaths("creation_record").build())));
    }

    @Test
    void listFiltersAndDeleteRemovesThePolicy() {
        policies.create(PARENT, policy("CPU", threshold("a")).toBuilder().putUserLabels("team", "ops").build());
        AlertPolicy memory = policies.create(PARENT, policy("Memory", threshold("a")));
        assertEquals(2, policies.list(PARENT, null, 0, null).getTotalSize());
        assertEquals(List.of("CPU"), policies.list(PARENT, "user_labels.team=\"ops\"", 0, null)
                .getAlertPoliciesList().stream().map(AlertPolicy::getDisplayName).toList());

        policies.delete(memory.getName());
        assertEquals(Status.Code.NOT_FOUND, code(() -> policies.get(memory.getName())));
        assertEquals(Status.Code.NOT_FOUND, code(() -> policies.delete(memory.getName())));
    }

    @Test
    void aReferencedChannelNeedsForceAndForceRemovesItFromPolicies() {
        NotificationChannel channel = channels.create(PARENT, email("ops@example.com"));
        AlertPolicy policy = policies.create(PARENT, policy("CPU", threshold("a")).toBuilder()
                .addNotificationChannels(channel.getName()).build());

        assertEquals(Status.Code.FAILED_PRECONDITION, code(() -> channels.delete(channel.getName(), false)));
        channels.delete(channel.getName(), true);
        assertEquals(Status.Code.NOT_FOUND, code(() -> channels.get(channel.getName())));
        assertEquals(0, policies.get(policy.getName()).getNotificationChannelsCount());
    }

    private static NotificationChannel sms(String number) {
        return NotificationChannel.newBuilder().setType("sms").putLabels("number", number).build();
    }

    private static NotificationChannel email(String address) {
        return NotificationChannel.newBuilder().setType("email").putLabels("email_address", address).build();
    }

    private static AlertPolicy policy(String displayName, AlertPolicy.Condition condition) {
        return AlertPolicy.newBuilder().setDisplayName(displayName).addConditions(condition).build();
    }

    private static AlertPolicy.Condition threshold(String displayName) {
        return AlertPolicy.Condition.newBuilder().setDisplayName(displayName)
                .setConditionThreshold(AlertPolicy.Condition.MetricThreshold.newBuilder()
                        .setFilter("metric.type=\"custom.googleapis.com/cpu\" AND resource.type=\"global\"")
                        .setComparison(ComparisonType.COMPARISON_GT).setThresholdValue(0.9)
                        .setDuration(Duration.newBuilder().setSeconds(60)))
                .build();
    }

    private static Status.Code code(Runnable call) {
        return assertThrows(GcpException.class, call::run).getGrpcCode();
    }
}
