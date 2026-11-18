package io.floci.gcp.services.cloudmonitoring;

import com.google.api.LabelDescriptor;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs each {@code uptime_url} uptime check every {@code period} and writes the
 * {@code monitoring.googleapis.com/uptime_check/*} series into Cloud Monitoring under the check's
 * project: {@code check_passed} (BOOL), {@code request_latency} (DOUBLE, ms), and for HTTP checks
 * {@code http_status} (STRING) and, when content matchers are set, {@code content_mismatch} (BOOL).
 * Checks on other resource types are stored but not probed.
 */
@ApplicationScoped
public class UptimeCheckProber {
    private static final Logger LOG = Logger.getLogger(UptimeCheckProber.class);
    static final String METRIC_PREFIX = "monitoring.googleapis.com/uptime_check/";
    static final String CHECKER_LOCATION = "local";
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    record Outcome(boolean passed, double latencyMs, Integer httpStatus, Boolean contentMismatch) {}

    private final UptimeCheckService checks;
    private final CloudMonitoringService monitoring;
    private final boolean enabled;
    private final long tickSeconds;
    private final ScheduledExecutorService executor;
    private final HttpClient validating;
    private final HttpClient trusting;
    private final Map<String, Instant> lastRun = new HashMap<>();
    private final Set<String> describedProjects = new HashSet<>();

    @Inject
    public UptimeCheckProber(UptimeCheckService checks, CloudMonitoringService monitoring, EmulatorConfig config) {
        this(checks, monitoring, config.services().monitoring().enabled() && config.services().monitoring().uptimeChecksEnabled(),
                config.services().monitoring().uptimeTickIntervalSeconds());
    }

    UptimeCheckProber(UptimeCheckService checks, CloudMonitoringService monitoring, boolean enabled, long tickSeconds) {
        this.checks = checks;
        this.monitoring = monitoring;
        this.enabled = enabled;
        this.tickSeconds = Math.max(1, tickSeconds);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "monitoring-uptime-prober");
            thread.setDaemon(true);
            return thread;
        });
        this.validating = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.trusting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).sslContext(trustAll()).build();
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.info("Cloud Monitoring uptime checks disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        LOG.infov("Cloud Monitoring uptime prober started (tick every {0}s)", tickSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void tickSafely() {
        try {
            tick(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("Cloud Monitoring uptime prober tick failed: {0}", t.getMessage());
        }
    }

    synchronized void tick(Instant now) {
        List<UptimeCheckConfig> all = checks.listAll();
        lastRun.keySet().retainAll(all.stream().map(UptimeCheckConfig::getName).toList());
        for (UptimeCheckConfig check : all) {
            if (!check.hasMonitoredResource() || !check.getMonitoredResource().getType().equals("uptime_url")) {
                continue;
            }
            Instant previous = lastRun.get(check.getName());
            if (previous != null && now.isBefore(previous.plusSeconds(check.getPeriod().getSeconds()))) {
                continue;
            }
            lastRun.put(check.getName(), now);
            try {
                record(check, probe(check), now);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Uptime check %s could not be recorded", check.getName());
            }
        }
    }

    Outcome probe(UptimeCheckConfig check) {
        Duration timeout = Duration.ofSeconds(check.getTimeout().getSeconds(), check.getTimeout().getNanos());
        String host = check.getMonitoredResource().getLabelsOrThrow("host");
        long started = System.nanoTime();
        if (check.hasTcpCheck()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, check.getTcpCheck().getPort()), (int) timeout.toMillis());
                return new Outcome(true, elapsedMs(started), null, null);
            } catch (Exception e) {
                return new Outcome(false, elapsedMs(started), null, null);
            }
        }
        UptimeCheckConfig.HttpCheck http = check.getHttpCheck();
        URI uri = URI.create((http.getUseSsl() ? "https" : "http") + "://" + host + ":" + http.getPort() + http.getPath());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        http.getHeadersMap().forEach((name, value) -> {
            try {
                request.header(name, value);
            } catch (IllegalArgumentException restricted) {
                LOG.debugf("Uptime check %s skips restricted header %s", check.getName(), name);
            }
        });
        if (http.hasAuthInfo()) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (http.getAuthInfo().getUsername() + ":" + http.getAuthInfo().getPassword()).getBytes(StandardCharsets.UTF_8)));
        }
        if (http.getRequestMethod() == UptimeCheckConfig.HttpCheck.RequestMethod.POST) {
            String contentType = switch (http.getContentType()) {
                case URL_ENCODED -> "application/x-www-form-urlencoded";
                case USER_PROVIDED -> http.getCustomContentType();
                default -> null;
            };
            if (contentType != null && !contentType.isEmpty()) {
                request.header("Content-Type", contentType);
            }
            request.POST(HttpRequest.BodyPublishers.ofByteArray(http.getBody().toByteArray()));
        } else {
            request.GET();
        }
        HttpResponse<byte[]> response;
        try {
            response = (http.getUseSsl() && http.getValidateSsl() ? validating : trusting)
                    .send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            return new Outcome(false, elapsedMs(started), null, check.getContentMatchersCount() > 0 ? Boolean.TRUE : null);
        }
        double latency = elapsedMs(started);
        boolean statusAccepted = accepted(http.getAcceptedResponseStatusCodesList(), response.statusCode());
        Boolean mismatch = null;
        if (check.getContentMatchersCount() > 0) {
            byte[] body = response.body();
            String text = new String(body, 0, Math.min(body.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
            mismatch = !check.getContentMatchersList().stream().allMatch(matcher -> matches(matcher, text));
        }
        return new Outcome(statusAccepted && !Boolean.TRUE.equals(mismatch), latency, response.statusCode(), mismatch);
    }

    private void record(UptimeCheckConfig check, Outcome outcome, Instant now) {
        String project = check.getName().substring(0, check.getName().indexOf("/uptimeCheckConfigs/"));
        String checkId = check.getName().substring(check.getName().lastIndexOf('/') + 1);
        MonitoredResource.Builder resource = check.getMonitoredResource().toBuilder();
        if (!resource.containsLabels("project_id")) {
            resource.putLabels("project_id", project.substring("projects/".length()));
        }
        describe(project);
        List<TimeSeries> series = new ArrayList<>();
        series.add(point("check_passed", resource, checkId, TypedValue.newBuilder().setBoolValue(outcome.passed()), now));
        series.add(point("request_latency", resource, checkId, TypedValue.newBuilder().setDoubleValue(outcome.latencyMs()), now));
        if (outcome.httpStatus() != null) {
            series.add(point("http_status", resource, checkId,
                    TypedValue.newBuilder().setStringValue(String.valueOf(outcome.httpStatus())), now));
        }
        if (outcome.contentMismatch() != null) {
            series.add(point("content_mismatch", resource, checkId,
                    TypedValue.newBuilder().setBoolValue(outcome.contentMismatch()), now));
        }
        monitoring.createTimeSeries(project, series);
    }

    /** The uptime metrics as the GCP metrics list defines them; auto-creation would not allow STRING. */
    private void describe(String project) {
        if (!describedProjects.add(project)) {
            return;
        }
        descriptor(project, "check_passed", "Check passed", MetricDescriptor.ValueType.BOOL, "");
        descriptor(project, "request_latency", "Request latency", MetricDescriptor.ValueType.DOUBLE, "ms");
        descriptor(project, "http_status", "HTTP status", MetricDescriptor.ValueType.STRING, "");
        descriptor(project, "content_mismatch", "Content mismatch", MetricDescriptor.ValueType.BOOL, "");
    }

    private void descriptor(String project, String metric, String displayName, MetricDescriptor.ValueType type, String unit) {
        monitoring.createMetricDescriptor(project, MetricDescriptor.newBuilder().setType(METRIC_PREFIX + metric)
                .setDisplayName(displayName).setMetricKind(MetricDescriptor.MetricKind.GAUGE).setValueType(type)
                .setUnit(unit)
                .addLabels(LabelDescriptor.newBuilder().setKey("check_id")
                        .setDescription("The Uptime check for which the request was made."))
                .addLabels(LabelDescriptor.newBuilder().setKey("checker_location")
                        .setDescription("For external checks, the Uptime Monitoring location making the request."))
                .build());
    }

    private static TimeSeries point(String metric, MonitoredResource.Builder resource, String checkId,
            TypedValue.Builder value, Instant now) {
        Timestamp end = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
        return TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder().setType(METRIC_PREFIX + metric)
                        .putLabels("check_id", checkId).putLabels("checker_location", CHECKER_LOCATION))
                .setResource(resource)
                .addPoints(Point.newBuilder().setInterval(TimeInterval.newBuilder().setEndTime(end)).setValue(value))
                .build();
    }

    private static boolean accepted(List<UptimeCheckConfig.HttpCheck.ResponseStatusCode> codes, int status) {
        if (codes.isEmpty()) {
            return status >= 200 && status < 300;
        }
        for (UptimeCheckConfig.HttpCheck.ResponseStatusCode code : codes) {
            if (code.hasStatusValue() && code.getStatusValue() == status) {
                return true;
            }
            if (code.hasStatusClass()) {
                int statusClass = code.getStatusClass().getNumber();
                if (statusClass == 1000 || (statusClass >= 100 && status / 100 == statusClass / 100)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(UptimeCheckConfig.ContentMatcher matcher, String body) {
        return switch (matcher.getMatcher()) {
            case NOT_CONTAINS_STRING -> !body.contains(matcher.getContent());
            case MATCHES_REGEX -> Pattern.compile(matcher.getContent()).matcher(body).find();
            case NOT_MATCHES_REGEX -> !Pattern.compile(matcher.getContent()).matcher(body).find();
            case MATCHES_JSON_PATH, NOT_MATCHES_JSON_PATH -> true;
            default -> body.contains(matcher.getContent());
        };
    }

    private static double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static SSLContext trustAll() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("No TLS context for uptime checks", e);
        }
    }
}
