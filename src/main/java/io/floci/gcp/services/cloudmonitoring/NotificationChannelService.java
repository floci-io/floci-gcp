package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.LabelDescriptor;
import com.google.api.LaunchStage;
import com.google.monitoring.v3.GetNotificationChannelVerificationCodeResponse;
import com.google.monitoring.v3.ListNotificationChannelDescriptorsResponse;
import com.google.monitoring.v3.ListNotificationChannelsResponse;
import com.google.monitoring.v3.MutationRecord;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.NotificationChannelDescriptor;
import com.google.protobuf.BoolValue;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * NotificationChannelService. Channels live in a global store keyed by full resource name, so the
 * future alert evaluator can read them without a request context.
 */
@ApplicationScoped
public class NotificationChannelService {
    private static final Logger LOG = Logger.getLogger(NotificationChannelService.class);

    private static final Pattern PARENT = Pattern.compile("projects/[^/\\s]+");
    private static final Pattern USER_LABEL_KEY = Pattern.compile("[a-z][a-z0-9_-]{0,62}");
    private static final Pattern USER_LABEL_VALUE = Pattern.compile("[a-z0-9_-]{0,63}");
    private static final Set<String> MUTABLE = Set.of("type", "display_name", "description", "labels",
            "user_labels", "enabled");
    private static final Duration SENT_CODE_LIFETIME = Duration.ofMinutes(30);
    private static final Duration COPY_CODE_LIFETIME = Duration.ofDays(1);
    private final SecureRandom random = new SecureRandom();

    /**
     * The channel types {@code channel-descriptors list} returns. Label keys come from the
     * channels API page and the Terraform provider tests; types whose keys are not documented
     * there carry no label schema and accept any labels.
     */
    static final Map<String, Descriptor> DESCRIPTORS = descriptors();

    record Descriptor(String displayName, List<String> labels, List<String> required, boolean verified,
            LaunchStage stage) {}

    private final StorageBackend<String, String> channels;
    private final ProjectRegistry projects;
    private final Clock clock;
    private final Map<String, Code> codes = new LinkedHashMap<>();
    private final Object lock = new Object();
    private Consumer<String> referenceRemover = name -> { };
    private Function<String, Boolean> referenceCheck = name -> false;

    private record Code(String value, Instant expires) {}

    @Inject
    public NotificationChannelService(StorageFactory storageFactory, ProjectRegistry projects) {
        this(storageFactory.createGlobal("monitoring", "monitoring-notification-channels.json",
                new TypeReference<Map<String, String>>() {}), projects, Clock.systemUTC());
    }

    NotificationChannelService(StorageBackend<String, String> channels, ProjectRegistry projects, Clock clock) {
        this.channels = channels;
        this.projects = projects;
        this.clock = clock;
    }

    /** Lets alert policies refuse or clean up a channel delete without a dependency cycle. */
    void onDelete(Function<String, Boolean> isReferenced, Consumer<String> removeReferences) {
        this.referenceCheck = isReferenced;
        this.referenceRemover = removeReferences;
    }

    // ── Descriptors ──────────────────────────────────────────────────────

    public ListNotificationChannelDescriptorsResponse listDescriptors(String parent, int pageSize, String pageToken) {
        String project = validateParent(parent);
        List<String> types = DESCRIPTORS.keySet().stream().sorted().toList();
        PageToken.Page<String> page = PageToken.paginate(types, pageSize > 0 ? Math.min(pageSize, types.size())
                : types.size(), pageToken);
        ListNotificationChannelDescriptorsResponse.Builder response = ListNotificationChannelDescriptorsResponse.newBuilder();
        page.items().forEach(type -> response.addChannelDescriptors(descriptor(project, type)));
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public NotificationChannelDescriptor getDescriptor(String name) {
        int at = name == null ? -1 : name.indexOf("/notificationChannelDescriptors/");
        if (at < 0) {
            throw GcpException.invalidArgument("Invalid notification channel descriptor name: " + name);
        }
        String project = validateParent(name.substring(0, at));
        String type = name.substring(at + "/notificationChannelDescriptors/".length());
        if (!DESCRIPTORS.containsKey(type)) {
            throw GcpException.notFound("Notification channel descriptor not found: " + name);
        }
        return descriptor(project, type);
    }

    // ── Channels ─────────────────────────────────────────────────────────

    public NotificationChannel create(String parent, NotificationChannel requested) {
        String project = validateParent(parent);
        if (requested.getVerificationStatus() != NotificationChannel.VerificationStatus.VERIFICATION_STATUS_UNSPECIFIED) {
            throw GcpException.invalidArgument("verification_status cannot be set when creating a notification channel");
        }
        validate(requested);
        synchronized (lock) {
            String name;
            do {
                name = project + "/notificationChannels/" + Long.toUnsignedString(random.nextLong() >>> 1);
            } while (channels.get(name).isPresent());
            NotificationChannel.Builder created = requested.toBuilder().setName(name)
                    .clearCreationRecord().clearMutationRecords().setCreationRecord(record());
            if (!requested.hasEnabled()) {
                created.setEnabled(BoolValue.of(true));
            }
            if (DESCRIPTORS.get(requested.getType()).verified()) {
                created.setVerificationStatus(NotificationChannel.VerificationStatus.UNVERIFIED);
            }
            NotificationChannel result = created.build();
            channels.put(name, ProtoJson.print(result));
            return result;
        }
    }

    public NotificationChannel get(String name) {
        String key = validateName(name);
        synchronized (lock) {
            return require(key);
        }
    }

    public ListNotificationChannelsResponse list(String parent, String filter, int pageSize, String pageToken) {
        String project = validateParent(parent);
        MonitoringFilterParser.Filter parsed = MonitoringFilterParser.parse(filter,
                MonitoringFilterParser.Scope.NOTIFICATION_CHANNELS);
        synchronized (lock) {
            List<NotificationChannel> matching = channels.keys().stream()
                    .filter(key -> key.startsWith(project + "/notificationChannels/")).sorted()
                    .map(this::require).filter(channel -> parsed.matches(object -> value(channel, object))).toList();
            PageToken.Page<NotificationChannel> page = PageToken.paginate(matching,
                    pageSize > 0 ? Math.min(pageSize, matching.size()) : matching.size(), pageToken);
            ListNotificationChannelsResponse.Builder response = ListNotificationChannelsResponse.newBuilder()
                    .addAllNotificationChannels(page.items()).setTotalSize(matching.size());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        }
    }

    public NotificationChannel update(NotificationChannel requested, FieldMask updateMask) {
        String key = validateName(requested.getName());
        synchronized (lock) {
            NotificationChannel current = require(key);
            NotificationChannel.Builder updated = current.toBuilder();
            if (updateMask.getPathsCount() == 0) {
                if (requested.getVerificationStatus() != current.getVerificationStatus()
                        && requested.getVerificationStatus() != NotificationChannel.VerificationStatus.VERIFICATION_STATUS_UNSPECIFIED) {
                    throw GcpException.invalidArgument("verification_status cannot be changed by UpdateNotificationChannel");
                }
                updated = requested.toBuilder().setName(key).setCreationRecord(current.getCreationRecord())
                        .clearMutationRecords().addAllMutationRecords(current.getMutationRecordsList())
                        .setVerificationStatus(current.getVerificationStatus());
                if (!requested.hasEnabled()) {
                    updated.setEnabled(BoolValue.of(true));
                }
            } else {
                for (String path : updateMask.getPathsList()) {
                    if (!MUTABLE.contains(path)) {
                        throw GcpException.invalidArgument("Invalid update mask path for a notification channel: " + path);
                    }
                }
                FieldMaskUtil.merge(updateMask, requested, updated, new FieldMaskUtil.MergeOptions()
                        .setReplaceRepeatedFields(true).setReplaceMessageFields(true));
            }
            boolean identityChanged = !updated.getType().equals(current.getType())
                    || !identity(updated.build()).equals(identity(current));
            if (identityChanged) {
                updated.setVerificationStatus(DESCRIPTORS.containsKey(updated.getType())
                        && DESCRIPTORS.get(updated.getType()).verified()
                        ? NotificationChannel.VerificationStatus.UNVERIFIED
                        : NotificationChannel.VerificationStatus.VERIFICATION_STATUS_UNSPECIFIED);
            }
            NotificationChannel result = updated.addMutationRecords(record()).build();
            validate(result);
            if (identityChanged) {
                codes.remove(key);
            }
            channels.put(key, ProtoJson.print(result));
            return result;
        }
    }

    public void delete(String name, boolean force) {
        String key = validateName(name);
        synchronized (lock) {
            require(key);
            if (!force && referenceCheck.apply(key)) {
                throw GcpException.failedPrecondition("Notification channel " + key
                        + " is referenced by an alerting policy; delete it with force=true to remove the references");
            }
            if (force) {
                referenceRemover.accept(key);
            }
            channels.delete(key);
            codes.remove(key);
        }
    }

    /**
     * Real Monitoring delivers the code to the channel. The emulator has no delivery path for it,
     * so the code is written to the emulator log, the only place a caller can read it.
     */
    public void sendVerificationCode(String name) {
        String key = validateName(name);
        synchronized (lock) {
            require(key);
            String code = "G-" + String.format("%06d", random.nextInt(1_000_000));
            codes.put(key, new Code(code, clock.instant().plus(SENT_CODE_LIFETIME)));
            LOG.infof("Notification channel %s verification code: %s", key, code);
        }
    }

    public GetNotificationChannelVerificationCodeResponse getVerificationCode(String name, Timestamp expireTime) {
        String key = validateName(name);
        synchronized (lock) {
            NotificationChannel channel = require(key);
            if (channel.getVerificationStatus() != NotificationChannel.VerificationStatus.VERIFIED) {
                throw GcpException.failedPrecondition("Notification channel " + key + " is not verified");
            }
            Instant now = clock.instant();
            Instant expires = now.plus(COPY_CODE_LIFETIME);
            if (expireTime != null && expireTime.getSeconds() > 0) {
                Instant requested = Instant.ofEpochSecond(expireTime.getSeconds(), expireTime.getNanos());
                if (requested.isAfter(now) && requested.isBefore(expires)) {
                    expires = requested;
                }
            }
            byte[] bytes = new byte[24];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            codes.put("copy:" + channel.getType() + ":" + identity(channel) + ":" + code, new Code(code, expires));
            return GetNotificationChannelVerificationCodeResponse.newBuilder().setCode(code)
                    .setExpireTime(timestamp(expires)).build();
        }
    }

    public NotificationChannel verify(String name, String code) {
        String key = validateName(name);
        if (code == null || code.isBlank()) {
            throw GcpException.invalidArgument("A verification code is required");
        }
        synchronized (lock) {
            NotificationChannel channel = require(key);
            Instant now = clock.instant();
            Code sent = codes.get(key);
            String copyKey = "copy:" + channel.getType() + ":" + identity(channel) + ":" + code;
            Code copied = codes.get(copyKey);
            boolean valid = (sent != null && sent.value().equals(code) && sent.expires().isAfter(now))
                    || (copied != null && copied.expires().isAfter(now));
            if (!valid) {
                throw GcpException.invalidArgument("The verification code is invalid or has expired");
            }
            if (sent != null && sent.value().equals(code)) {
                codes.remove(key);
            }
            NotificationChannel verified = channel.toBuilder()
                    .setVerificationStatus(NotificationChannel.VerificationStatus.VERIFIED)
                    .addMutationRecords(record()).build();
            channels.put(key, ProtoJson.print(verified));
            return verified;
        }
    }

    Object lock() {
        return lock;
    }

    /** A channel by name, for the evaluator; empty when it no longer exists. */
    Optional<NotificationChannel> find(String name) {
        synchronized (lock) {
            return channels.get(canonical(name)).map(json -> ProtoJson.merge(json, NotificationChannel.newBuilder()).build());
        }
    }

    /** The code the last SendNotificationChannelVerificationCode logged for a channel, for tests. */
    String sentCode(String name) {
        synchronized (lock) {
            Code code = codes.get(canonical(name));
            return code == null ? null : code.value();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void validate(NotificationChannel channel) {
        if (channel.getType().isEmpty()) {
            throw GcpException.invalidArgument("A notification channel requires a type");
        }
        Descriptor descriptor = DESCRIPTORS.get(channel.getType());
        if (descriptor == null) {
            throw GcpException.invalidArgument("Unknown notification channel type: " + channel.getType());
        }
        if (!descriptor.labels().isEmpty()) {
            for (String key : channel.getLabelsMap().keySet()) {
                if (!descriptor.labels().contains(key)) {
                    throw GcpException.invalidArgument("Label " + key + " is not defined for channel type "
                            + channel.getType());
                }
            }
            for (String key : descriptor.required()) {
                if (channel.getLabelsMap().getOrDefault(key, "").isEmpty()) {
                    throw GcpException.invalidArgument("Channel type " + channel.getType() + " requires label " + key);
                }
            }
        }
        if (channel.getDisplayName().codePointCount(0, channel.getDisplayName().length()) > 512) {
            throw GcpException.invalidArgument("The display name is limited to 512 characters");
        }
        if (channel.getDescription().codePointCount(0, channel.getDescription().length()) > 1024) {
            throw GcpException.invalidArgument("The description may not exceed 1024 characters");
        }
        if (channel.getUserLabelsCount() > 64) {
            throw GcpException.invalidArgument("A notification channel can have at most 64 user labels");
        }
        channel.getUserLabelsMap().forEach((key, value) -> {
            if (!USER_LABEL_KEY.matcher(key).matches() || !USER_LABEL_VALUE.matcher(value).matches()) {
                throw GcpException.invalidArgument("Invalid user label " + key + "=" + value);
            }
        });
    }

    private static String value(NotificationChannel channel, String object) {
        return switch (object) {
            case "type" -> channel.getType();
            case "display_name" -> channel.getDisplayName();
            case "description" -> channel.getDescription();
            case "enabled" -> String.valueOf(!channel.hasEnabled() || channel.getEnabled().getValue());
            case "verification_status" -> channel.getVerificationStatus().name();
            default -> {
                if (object.startsWith("labels.")) {
                    yield channel.getLabelsMap().get(object.substring("labels.".length()));
                }
                if (object.startsWith("user_labels.")) {
                    yield channel.getUserLabelsMap().get(object.substring("user_labels.".length()));
                }
                yield null;
            }
        };
    }

    private static String identity(NotificationChannel channel) {
        return new TreeMap<>(channel.getLabelsMap()).toString();
    }

    private NotificationChannel require(String key) {
        return channels.get(key).map(json -> ProtoJson.merge(json, NotificationChannel.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Notification channel not found: " + key));
    }

    private String validateParent(String parent) {
        if (parent == null || !PARENT.matcher(parent).matches()) {
            throw GcpException.invalidArgument("Invalid project name: " + parent);
        }
        return projects == null ? parent : projects.canonicalProjectName(parent);
    }

    private String validateName(String name) {
        if (name == null || !name.matches("projects/[^/\\s]+/notificationChannels/[^/\\s]+")) {
            throw GcpException.invalidArgument("Invalid notification channel name: " + name);
        }
        return canonical(name);
    }

    private String canonical(String name) {
        return projects == null ? name : projects.canonicalResourceName(name);
    }

    private MutationRecord record() {
        return MutationRecord.newBuilder().setMutateTime(timestamp(clock.instant())).build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static NotificationChannelDescriptor descriptor(String project, String type) {
        Descriptor descriptor = DESCRIPTORS.get(type);
        NotificationChannelDescriptor.Builder result = NotificationChannelDescriptor.newBuilder()
                .setName(project + "/notificationChannelDescriptors/" + type).setType(type)
                .setDisplayName(descriptor.displayName()).setLaunchStage(descriptor.stage());
        descriptor.labels().forEach(key -> result.addLabels(LabelDescriptor.newBuilder().setKey(key)));
        return result.build();
    }

    private static Map<String, Descriptor> descriptors() {
        Map<String, Descriptor> all = new LinkedHashMap<>();
        all.put("email", new Descriptor("Email", List.of("email_address"), List.of("email_address"), false, LaunchStage.GA));
        all.put("pubsub", new Descriptor("Pub/Sub", List.of("topic"), List.of("topic"), false, LaunchStage.GA));
        all.put("slack", new Descriptor("Slack", List.of("auth_token", "channel_name"), List.of(), false, LaunchStage.GA));
        all.put("webhook_basicauth", new Descriptor("Webhooks with basic authentication",
                List.of("url", "username", "password"), List.of("url"), false, LaunchStage.GA));
        all.put("webhook_tokenauth", new Descriptor("Webhooks with token authentication", List.of("url"),
                List.of("url"), false, LaunchStage.GA));
        all.put("pagerduty", new Descriptor("PagerDuty", List.of("service_key"), List.of(), false, LaunchStage.GA));
        all.put("sms", new Descriptor("SMS", List.of(), List.of(), true, LaunchStage.GA));
        all.put("google_chat", new Descriptor("Google Chat", List.of(), List.of(), false, LaunchStage.GA));
        all.put("campfire", new Descriptor("Campfire", List.of(), List.of(), false, LaunchStage.DEPRECATED));
        all.put("hipchat", new Descriptor("HipChat", List.of(), List.of(), false, LaunchStage.DEPRECATED));
        return Map.copyOf(all);
    }
}
