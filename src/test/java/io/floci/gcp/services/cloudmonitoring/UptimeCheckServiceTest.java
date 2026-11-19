package io.floci.gcp.services.cloudmonitoring;

import com.google.api.MonitoredResource;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UptimeCheckServiceTest {
    private static final String PROJECT = "projects/test-project";
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private UptimeCheckService checks;
    private CloudMonitoringService monitoring;
    private UptimeCheckProber prober;
    private HttpServer server;
    private ExecutorService serverThreads;
    private volatile int status = 200;
    private volatile String body = "service is healthy";
    private volatile long delayMs = 0;

    @BeforeEach
    void setUp() throws Exception {
        checks = new UptimeCheckService(new InMemoryStorage<>(), null, null);
        monitoring = new CloudMonitoringService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
        prober = new UptimeCheckProber(checks, monitoring, true, 1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    // ── Configuration ────────────────────────────────────────────────────

    @Test
    void createAssignsANameAndFillsTheDefaults() {
        UptimeCheckConfig created = checks.create(PROJECT, http("Home page", "/").build());

        assertTrue(created.getName().matches(PROJECT + "/uptimeCheckConfigs/home-page-[0-9a-f]+"), created.getName());
        assertEquals(60, created.getPeriod().getSeconds());
        assertEquals(UptimeCheckConfig.HttpCheck.RequestMethod.GET, created.getHttpCheck().getRequestMethod());
        assertEquals(port(), created.getHttpCheck().getPort());
        assertEquals(created, checks.get(created.getName()));
        assertEquals(1, checks.list(PROJECT, 0, null).getTotalSize());
        assertEquals(4, checks.listIps(0, null).getUptimeCheckIpsCount());
    }

    @Test
    void createRejectsWhatTheProtoForbids() {
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT,
                http("odd period", "/").setPeriod(Duration.newBuilder().setSeconds(120)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT,
                http("no timeout", "/").clearTimeout().build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT,
                http("slow", "/").setTimeout(Duration.newBuilder().setSeconds(61)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT,
                http("no target", "/").clearMonitoredResource().build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT,
                http("no check", "/").clearHttpCheck().build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT, http("no host", "/")
                .setMonitoredResource(MonitoredResource.newBuilder().setType("uptime_url")).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT, http("bad regex", "/")
                .addContentMatchers(regex("[", UptimeCheckConfig.ContentMatcher.ContentMatcherOption.MATCHES_REGEX)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.create(PROJECT, http("bad negated regex", "/")
                .addContentMatchers(regex("(", UptimeCheckConfig.ContentMatcher.ContentMatcherOption.NOT_MATCHES_REGEX)).build())));
    }

    @Test
    void updatesOnlyTouchUpdatableFieldsAndKeepTheTarget() {
        UptimeCheckConfig created = checks.create(PROJECT, http("Home", "/").build());

        UptimeCheckConfig renamed = checks.update(created.toBuilder().setDisplayName("Renamed")
                .setTimeout(Duration.newBuilder().setSeconds(5)).build(),
                FieldMask.newBuilder().addPaths("display_name").build());
        assertEquals("Renamed", renamed.getDisplayName());
        assertEquals(10, renamed.getTimeout().getSeconds());

        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.update(renamed,
                FieldMask.newBuilder().addPaths("period").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.update(renamed,
                FieldMask.newBuilder().addPaths("http_check.typo").build())));
        assertEquals("/v2", checks.update(renamed.toBuilder().setHttpCheck(renamed.getHttpCheck().toBuilder().setPath("/v2"))
                .build(), FieldMask.newBuilder().addPaths("http_check.path").build()).getHttpCheck().getPath());
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> checks.update(renamed.toBuilder()
                .setMonitoredResource(MonitoredResource.newBuilder().setType("uptime_url").putLabels("host", "other"))
                .build(), FieldMask.getDefaultInstance())));

        checks.delete(created.getName());
        assertEquals(Status.Code.NOT_FOUND, code(() -> checks.get(created.getName())));
    }

    // ── Probing ──────────────────────────────────────────────────────────

    @Test
    void aHealthyHttpTargetWritesPassingSeriesLabelledWithTheCheck() {
        UptimeCheckConfig check = checks.create(PROJECT, http("Home", "/health").addContentMatchers(
                UptimeCheckConfig.ContentMatcher.newBuilder().setContent("healthy")).build());

        prober.tick(NOW).join();

        String checkId = check.getName().substring(check.getName().lastIndexOf('/') + 1);
        TimeSeries passed = only("check_passed");
        assertTrue(passed.getPoints(0).getValue().getBoolValue());
        assertEquals(Map.of("check_id", checkId, "checker_location", "local"), passed.getMetric().getLabelsMap());
        assertEquals("uptime_url", passed.getResource().getType());
        assertEquals(Map.of("host", "127.0.0.1", "project_id", "test-project"), passed.getResource().getLabelsMap());
        assertEquals("200", only("http_status").getPoints(0).getValue().getStringValue());
        assertFalse(only("content_mismatch").getPoints(0).getValue().getBoolValue());
        assertTrue(only("request_latency").getPoints(0).getValue().getDoubleValue() >= 0);
    }

    @Test
    void aWrongStatusAMismatchOrATimeoutFailsTheCheck() {
        checks.create(PROJECT, http("Home", "/").build());
        status = 503;
        prober.tick(NOW).join();
        assertFalse(only("check_passed").getPoints(0).getValue().getBoolValue());
        assertEquals("503", only("http_status").getPoints(0).getValue().getStringValue());

        status = 200;
        body = "maintenance";
        checks.create(PROJECT, http("Content", "/").addContentMatchers(UptimeCheckConfig.ContentMatcher.newBuilder()
                .setContent("healthy")).build());
        delayMs = 0;
        checks.create(PROJECT, http("Slow", "/").setTimeout(Duration.newBuilder().setSeconds(1)).build());
        delayMs = 1500;
        prober.tick(NOW.plusSeconds(60)).join();
        delayMs = 0;

        Map<String, Boolean> passedByCheck = all("check_passed").stream().collect(Collectors.groupingBy(
                ts -> ts.getMetric().getLabelsOrThrow("check_id").replaceAll("-[0-9a-f]+$", ""),
                Collectors.reducing(Boolean.TRUE, ts -> ts.getPoints(0).getValue().getBoolValue(), Boolean::logicalAnd)));
        assertFalse(passedByCheck.get("content"));
        assertFalse(passedByCheck.get("slow"));
    }

    @Test
    void acceptedStatusClassesAndTcpChecksAreHonoured() throws Exception {
        status = 404;
        checks.create(PROJECT, http("Missing ok", "/").setHttpCheck(http("x", "/").getHttpCheck().toBuilder()
                .addAcceptedResponseStatusCodes(UptimeCheckConfig.HttpCheck.ResponseStatusCode.newBuilder()
                        .setStatusClass(UptimeCheckConfig.HttpCheck.ResponseStatusCode.StatusClass.STATUS_CLASS_4XX))).build());
        try (ServerSocket closed = new ServerSocket(0)) {
            int closedPort = closed.getLocalPort();
            closed.close();
            checks.create(PROJECT, tcp("TCP open", port()).build());
            checks.create(PROJECT, tcp("TCP closed", closedPort).build());
        }

        prober.tick(NOW).join();

        Map<String, Boolean> passed = all("check_passed").stream().collect(Collectors.toMap(
                ts -> ts.getMetric().getLabelsOrThrow("check_id").replaceAll("-[0-9a-f]+$", ""),
                ts -> ts.getPoints(0).getValue().getBoolValue()));
        assertEquals(Map.of("missing-ok", true, "tcp-open", true, "tcp-closed", false), passed);
    }

    @Test
    void eachCheckRunsOncePerPeriodAndOtherResourceTypesAreNotProbed() {
        checks.create(PROJECT, http("Home", "/").setPeriod(Duration.newBuilder().setSeconds(300)).build());
        checks.create(PROJECT, http("VM", "/").setMonitoredResource(MonitoredResource.newBuilder()
                .setType("gce_instance").putLabels("instance_id", "1").putLabels("zone", "us-central1-a")).build());

        prober.tick(NOW).join();
        prober.tick(NOW.plusSeconds(60)).join();
        prober.tick(NOW.plusSeconds(301)).join();

        assertEquals(1, all("check_passed").size());
        assertEquals(2, all("check_passed").getFirst().getPointsCount());
    }

    @Test
    void aBacktrackingRegexFailsItsCheckWithoutHoldingUpTheOthers() {
        body = "a".repeat(64) + "!";
        checks.create(PROJECT, http("Backtracking", "/").addContentMatchers(
                regex("(a+){10}$", UptimeCheckConfig.ContentMatcher.ContentMatcherOption.MATCHES_REGEX)).build());
        checks.create(PROJECT, http("Negated backtracking", "/").addContentMatchers(
                regex("(a+){10}$", UptimeCheckConfig.ContentMatcher.ContentMatcherOption.NOT_MATCHES_REGEX)).build());
        checks.create(PROJECT, http("Plain", "/").addContentMatchers(
                UptimeCheckConfig.ContentMatcher.newBuilder().setContent("aaa")).build());

        long started = System.nanoTime();
        prober.tick(NOW).join();

        assertTrue(System.nanoTime() - started < 10_000_000_000L, "the tick finished");
        assertEquals(Map.of("backtracking", false, "negated-backtracking", false, "plain", true), passedByCheck());
    }

    @Test
    void slowTargetsAreProbedInParallel() {
        delayMs = 1500;
        for (int i = 0; i < 4; i++) {
            checks.create(PROJECT, http("Slow " + i, "/").setTimeout(Duration.newBuilder().setSeconds(10)).build());
        }

        long started = System.nanoTime();
        prober.tick(NOW).join();

        assertTrue(System.nanoTime() - started < 4_500_000_000L, "four 1.5 s probes ran in parallel");
        assertEquals(4, passedByCheck().values().stream().filter(Boolean::booleanValue).count());
        prober.tick(NOW.plusSeconds(1)).join();
        assertEquals(4, all("check_passed").size(), "a check is not started again before its period");
    }

    @Test
    void contentMatchersReadOnlyTheFirstMebibyteOfTheBody() {
        body = "healthy" + "x".repeat(2 * 1024 * 1024) + "tail-marker";
        checks.create(PROJECT, http("Head", "/").addContentMatchers(
                UptimeCheckConfig.ContentMatcher.newBuilder().setContent("healthy")).build());
        checks.create(PROJECT, http("Tail", "/").addContentMatchers(
                UptimeCheckConfig.ContentMatcher.newBuilder().setContent("tail-marker")).build());

        prober.tick(NOW).join();

        assertEquals(Map.of("head", true, "tail", false), passedByCheck());
    }

    @Test
    void theCappedBodyStopsReadingAtItsLimit() {
        AtomicBoolean cancelled = new AtomicBoolean();
        UptimeCheckProber.CappedBody capped = new UptimeCheckProber.CappedBody(4);
        capped.onSubscribe(subscription(cancelled));
        capped.onNext(List.of(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8)),
                ByteBuffer.wrap("defgh".getBytes(StandardCharsets.UTF_8))));
        assertTrue(cancelled.get());
        assertEquals("abcd", new String(capped.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8));

        AtomicBoolean discarded = new AtomicBoolean();
        UptimeCheckProber.CappedBody none = new UptimeCheckProber.CappedBody(0);
        none.onSubscribe(subscription(discarded));
        assertTrue(discarded.get());
        assertEquals(0, none.getBody().toCompletableFuture().join().length);
    }

    private static Flow.Subscription subscription(AtomicBoolean cancelled) {
        return new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        };
    }

    private static UptimeCheckConfig.ContentMatcher regex(String pattern,
            UptimeCheckConfig.ContentMatcher.ContentMatcherOption option) {
        return UptimeCheckConfig.ContentMatcher.newBuilder().setContent(pattern).setMatcher(option).build();
    }

    private Map<String, Boolean> passedByCheck() {
        return all("check_passed").stream().collect(Collectors.toMap(
                ts -> ts.getMetric().getLabelsOrThrow("check_id").replaceAll("-[0-9a-f]+$", ""),
                ts -> ts.getPoints(0).getValue().getBoolValue()));
    }

    private TimeSeries only(String metric) {
        List<TimeSeries> series = all(metric);
        assertEquals(1, series.size(), series.toString());
        return series.getFirst();
    }

    private List<TimeSeries> all(String metric) {
        return monitoring.listTimeSeries(PROJECT, "metric.type=\"" + UptimeCheckProber.METRIC_PREFIX + metric + "\"",
                TimeInterval.newBuilder().setStartTime(ts(NOW.minusSeconds(60))).setEndTime(ts(NOW.plusSeconds(600))).build(),
                Aggregation.getDefaultInstance(), "FULL", 0, null).items();
    }

    private UptimeCheckConfig.Builder http(String displayName, String path) {
        return UptimeCheckConfig.newBuilder().setDisplayName(displayName)
                .setMonitoredResource(MonitoredResource.newBuilder().setType("uptime_url").putLabels("host", "127.0.0.1"))
                .setHttpCheck(UptimeCheckConfig.HttpCheck.newBuilder().setPath(path).setPort(port()))
                .setTimeout(Duration.newBuilder().setSeconds(10));
    }

    private UptimeCheckConfig.Builder tcp(String displayName, int port) {
        return UptimeCheckConfig.newBuilder().setDisplayName(displayName)
                .setMonitoredResource(MonitoredResource.newBuilder().setType("uptime_url").putLabels("host", "127.0.0.1"))
                .setTcpCheck(UptimeCheckConfig.TcpCheck.newBuilder().setPort(port))
                .setTimeout(Duration.newBuilder().setSeconds(2));
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Status.Code code(Runnable call) {
        return assertThrows(GcpException.class, call::run).getGrpcCode();
    }
}
