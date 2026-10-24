package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.compute.ComputeMetadataTree.Dir;
import io.floci.gcp.services.compute.ComputeMetadataTree.Leaf;
import io.floci.gcp.services.compute.ComputeMetadataTree.Node;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The GCE metadata server, served on the emulator's main port under {@code /computeMetadata/v1}. Instance
 * containers reach it through {@code GCE_METADATA_HOST}, which the Google client libraries honour. The calling
 * instance is the one whose container has the request's source address, as on GCE, where the server knows the VM
 * that asks. Requests need {@code Metadata-Flavor: Google} and must not carry {@code X-Forwarded-For}.
 *
 * <p>Status codes for those rejections and for unknown keys are not in the docs; this uses 403 and 404, and the
 * client libraries rely only on 404 meaning "not defined".
 */
@ApplicationScoped
@Path("/computeMetadata/v1")
public class ComputeMetadataController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEXT = "application/text";
    private static final String JSON_TYPE = "application/json";
    private static final long POLL_MILLIS = 250;

    private final ComputeService compute;
    private final ComputeInstanceRuntime runtime;
    private final EmulatorConfig config;
    /** Re-checks waiting wait_for_change requests, so a waiter never holds an HTTP worker. */
    private final ScheduledExecutorService waits = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "compute-metadata-wait");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public ComputeMetadataController(ComputeService compute, ComputeInstanceRuntime runtime, EmulatorConfig config) {
        this.compute = compute;
        this.runtime = runtime;
        this.config = config;
    }

    @PreDestroy
    void close() {
        waits.shutdownNow();
    }

    @GET
    @Blocking
    public Uni<Response> root(@Context HttpHeaders headers, @Context HttpServerRequest request) {
        return get("", headers, request);
    }

    @GET
    @Blocking
    @Path("{path:.*}")
    public Uni<Response> get(@PathParam("path") String path, @Context HttpHeaders headers,
                             @Context HttpServerRequest request) {
        return answer(path, headers, request);
    }

    private Uni<Response> answer(String path, HttpHeaders headers, HttpServerRequest request) {
        if (!config.services().compute().metadataServer().enabled()) {
            return now(plain(404, "The metadata server is disabled."));
        }
        if (headers.getHeaderString("X-Forwarded-For") != null) {
            return now(plain(403, "Request contains an X-Forwarded-For header and was rejected."));
        }
        if (!"Google".equals(headers.getHeaderString("Metadata-Flavor"))) {
            return now(plain(403, "Missing Metadata-Flavor:Google header."));
        }
        // The path parameter loses a trailing slash, which decides between a value and a directory listing.
        boolean slash = path.isEmpty() || request.path().endsWith("/");
        String address = request.remoteAddress() == null ? "" : request.remoteAddress().hostAddress();
        Optional<ComputeInstanceRuntime.InstanceRef> caller = runtime.instanceAt(address);
        if (caller.isEmpty()) {
            return now(plain(404, "No Compute instance container has the address " + address + "."));
        }
        ComputeInstanceRuntime.InstanceRef instance = caller.get();
        MultiMap query = request.params();
        boolean recursive = "true".equalsIgnoreCase(query.get("recursive"));
        String alt = query.get("alt");
        boolean wait = "true".equalsIgnoreCase(query.get("wait_for_change"));

        Optional<Rendered> first = render(instance, path, slash, recursive, alt, query.get("audience"),
                query.get("format"), query.get("licenses"), request.query());
        if (first.isEmpty()) { return now(plain(404, "Not Found")); }
        Rendered rendered = first.get();
        if (rendered.response() != null) { return now(rendered.response()); }
        if (wait) {
            if (rendered.generated() || (rendered.directory() && !recursive)) {
                return now(plain(400, "Invalid request: wait_for_change is not supported here."));
            }
            String lastEtag = query.get("last_etag");
            Duration timeout = waitTimeout(query.get("timeout_sec"));
            if (timeout == null) {
                return now(plain(400, "Invalid request: timeout_sec must be a positive integer."));
            }
            if (lastEtag == null || lastEtag.equals(rendered.etag())) {
                return waitForChange(instance, path, slash, recursive, alt, rendered.etag(),
                        System.nanoTime() + timeout.toNanos());
            }
        }
        return now(ok(rendered));
    }

    /**
     * Answers once the value's ETag differs from {@code baseline} or the deadline passes, checking every
     * {@link #POLL_MILLIS} on the wait scheduler; a caller that disconnects stops the checks.
     */
    private Uni<Response> waitForChange(ComputeInstanceRuntime.InstanceRef instance, String path, boolean slash,
                                        boolean recursive, String alt, String baseline, long deadline) {
        return Uni.createFrom().emitter(emitter -> {
            AtomicReference<ScheduledFuture<?>> tick = new AtomicReference<>();
            tick.set(waits.scheduleWithFixedDelay(() -> {
                try {
                    Optional<Rendered> next = render(instance, path, slash, recursive, alt, null, null, null);
                    if (next.isEmpty()) {
                        emitter.complete(plain(404, "Not Found"));
                    } else if (!next.get().etag().equals(baseline) || System.nanoTime() >= deadline) {
                        emitter.complete(ok(next.get()));
                    } else {
                        return;
                    }
                } catch (RuntimeException e) {
                    emitter.fail(e);
                }
                ScheduledFuture<?> self = tick.get();
                if (self != null) { self.cancel(false); }
            }, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS));
            emitter.onTermination(() -> {
                ScheduledFuture<?> self = tick.get();
                if (self != null) { self.cancel(false); }
            });
        });
    }

    private static Uni<Response> now(Response response) {
        return Uni.createFrom().item(response);
    }

    private static Response ok(Rendered rendered) {
        return Response.ok(rendered.body())
                .header("Metadata-Flavor", "Google")
                .header("ETag", rendered.etag())
                .header("Server", "Metadata Server for VM")
                .type(rendered.contentType())
                .build();
    }

    private record Rendered(String body, String contentType, String etag, boolean directory, boolean generated,
                            Response response) {}

    private Optional<Rendered> render(ComputeInstanceRuntime.InstanceRef instance, String path, boolean slash, boolean recursive,
                                      String alt, String audience, String format, String licenses) {
        return render(instance, path, slash, recursive, alt, audience, format, licenses, null);
    }

    private Optional<Rendered> render(ComputeInstanceRuntime.InstanceRef instance, String path, boolean slash, boolean recursive,
                                      String alt, String audience, String format, String licenses, String rawQuery) {
        Optional<ComputeService.MetadataView> view = compute.metadataView(instance.project(), instance.zone(), instance.name());
        if (view.isEmpty()) { return Optional.empty(); }
        Dir tree = ComputeMetadataTree.build(instance.project(), instance.zone(), view.get());
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        Node node = tree;
        if (!trimmed.isEmpty()) {
            for (String part : trimmed.split("/")) {
                if (!(node instanceof Dir dir) || !dir.children().containsKey(part)) { return Optional.empty(); }
                node = dir.children().get(part);
            }
        }
        if (node instanceof Leaf leaf && leaf.generated()) {
            String[] parts = trimmed.split("/");
            String email = accountEmail(tree, parts[parts.length - 2]);
            if (trimmed.endsWith("/token")) {
                return Optional.of(generated(token(), JSON_TYPE));
            }
            if (audience == null || audience.isBlank()) {
                return Optional.of(new Rendered(null, null, null, false, true,
                        plain(400, "non-empty audience parameter required")));
            }
            return Optional.of(generated(identity(instance, view.get(), email, audience, format, licenses), TEXT));
        }
        if (node instanceof Dir dir) {
            if (!slash) {
                return Optional.of(new Rendered(null, null, null, true, false,
                        Response.status(301).location(URI.create("/computeMetadata/v1/" + path + "/"
                                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery)))
                                .header("Metadata-Flavor", "Google").build()));
            }
            if (recursive) {
                String body = "text".equals(alt) ? ComputeMetadataTree.text(dir, "") : write(ComputeMetadataTree.json(dir));
                return Optional.of(rendered(body, "text".equals(alt) ? TEXT : JSON_TYPE, true));
            }
            StringBuilder listing = new StringBuilder();
            new TreeMap<>(dir.children()).forEach((key, child) ->
                    listing.append(key).append(child instanceof Dir ? "/" : "").append('\n'));
            return Optional.of(rendered(listing.toString(), TEXT, true));
        }
        Leaf leaf = (Leaf) node;
        boolean json = "json".equals(alt) || (alt == null && trimmed.equals("instance/tags"));
        return Optional.of(rendered(json ? write(leaf.json()) : leaf.text(), json ? JSON_TYPE : TEXT, false));
    }

    /** The email of a service account directory, which is listed both as {@code default} and by its email. */
    private static String accountEmail(Dir tree, String account) {
        Node node = tree;
        for (String part : new String[] {"instance", "service-accounts", account, "email"}) {
            node = ((Dir) node).children().get(part);
        }
        return ((Leaf) node).text();
    }

    private static Rendered rendered(String body, String type, boolean directory) {
        return new Rendered(body, type, etag(body), directory, false, null);
    }

    private static Rendered generated(String body, String type) {
        return new Rendered(body, type, etag(body), false, true, null);
    }

    private Duration waitTimeout(String value) {
        if (value == null) { return config.services().compute().metadataServer().defaultWaitTimeout(); }
        try {
            long seconds = Long.parseLong(value);
            // An integer in the docs; the bound keeps the deadline arithmetic in range.
            return seconds > 0 && seconds <= Integer.MAX_VALUE ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A stub access token: floci-gcp accepts any credential, so only the shape matters to clients. */
    private static String token() {
        ObjectNode token = JSON.createObjectNode()
                .put("access_token", "ya29.floci-" + UUID.randomUUID().toString().replace("-", ""))
                .put("expires_in", 3599)
                .put("token_type", "Bearer");
        return write(token);
    }

    /**
     * An ID token for {@code audience}. It is shaped like Google's (three base64url parts, RS256 header) but not
     * signed by a Google key, so only code that skips verification accepts it.
     */
    private static String identity(ComputeInstanceRuntime.InstanceRef instance, ComputeService.MetadataView view,
                                   String email, String audience, String format, String licenses) {
        long now = Instant.now().getEpochSecond();
        ObjectNode claims = JSON.createObjectNode()
                .put("aud", audience).put("azp", email).put("email", email).put("email_verified", true)
                .put("exp", now + 3600).put("iat", now).put("iss", "https://accounts.google.com")
                .put("sub", Long.toUnsignedString(email.hashCode() & 0xffffffffL));
        if ("full".equals(format)) {
            ObjectNode engine = claims.putObject("google").putObject("compute_engine")
                    .put("instance_creation_timestamp",
                            Instant.parse(view.instance().path("creationTimestamp").asText(Instant.now().toString()))
                                    .getEpochSecond())
                    .put("instance_id", view.instance().path("id").asText())
                    .put("instance_name", instance.name())
                    .put("project_id", instance.project())
                    .put("project_number", Long.parseLong(ProjectNumbers.of(instance.project())))
                    .put("zone", instance.zone());
            if ("TRUE".equalsIgnoreCase(licenses)) { engine.putArray("license_id"); }
        }
        ObjectNode header = JSON.createObjectNode().put("alg", "RS256").put("kid", "floci-gcp").put("typ", "JWT");
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(write(claims).getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString("floci-gcp-unsigned".getBytes(StandardCharsets.UTF_8));
    }

    private static String etag(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response plain(int status, String body) {
        return Response.status(status).entity(body).type(TEXT).header("Metadata-Flavor", "Google").build();
    }
}
