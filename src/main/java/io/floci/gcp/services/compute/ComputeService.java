package io.floci.gcp.services.compute;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.ProjectAwareStorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.compute.model.ComputeOperation;
import io.floci.gcp.services.compute.model.ComputeProject;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@ApplicationScoped
public class ComputeService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[a-z](?:[-a-z0-9]{0,61}[a-z0-9])?");
    private static final Set<String> LABELLED = Set.of("instances", "disks", "images", "snapshots", "addresses", "forwardingRules");
    private final ProjectAwareStorageBackend<ComputeProject> store;
    private final EmulatorConfig config;
    private final ServiceRegistry registry;
    private final Instance<ComputeResourceHandler> handlers;

    @Inject
    public ComputeService(StorageFactory factory, EmulatorConfig config, ServiceRegistry registry,
                          Instance<ComputeResourceHandler> handlers) {
        this.store = (ProjectAwareStorageBackend<ComputeProject>) factory.<ComputeProject>create("compute", "compute.json", new TypeReference<Map<String, ComputeProject>>() {});
        this.config = config;
        this.registry = registry;
        this.handlers = handlers;
    }
    void start(@Observes StartupEvent event) {
        registry.register(ServiceDescriptor.builder("compute").enabled(config.services().compute().enabled())
                .storageKey("compute").resourceClasses(ComputeController.class).build());
    }
    private ComputeProject state(String project) {
        if (project == null || !project.matches("[A-Za-z0-9][A-Za-z0-9:.-]{0,127}")) {
            throw GcpException.invalidArgument("Invalid project");
        }
        ComputeProject state = JSON.convertValue(store.getForProject(project, "state").orElseGet(ComputeProject::new), ComputeProject.class);
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (ComputeOperation op : state.operations.values()) {
            if (!op.response.path("status").asText().equals("DONE")) {
                String status = now >= op.readyAt ? "DONE" : now >= op.startedAt ? "RUNNING" : "PENDING";
                op.response.put("status", status).put("progress", status.equals("DONE") ? 100 : status.equals("RUNNING") ? 50 : 0);
                if (!status.equals("PENDING")) {
                    op.response.put("startTime", Instant.ofEpochMilli(op.startedAt).toString());
                }
                if (status.equals("DONE")) {
                    op.response.put("endTime", Instant.ofEpochMilli(op.readyAt).toString());
                    op.finalStates.forEach((key, value) -> {
                        ObjectNode resource = state.resources.get(key);
                        if (resource != null) { resource.put("status", value); }
                    });
                }
                changed = true;
            }
        }
        if (changed) { save(project, state); }
        return state;
    }
    private void save(String project, ComputeProject state) {
        store.putForProject(project, "state", state);
        store.checkpoint();
    }
    private Context context(String project, String path, ComputeProject state) {
        String[] parts = path.split("/");
        int index;
        String scope;
        if (Set.of("regions", "zones").contains(parts[0]) && parts.length <= 2) {
            index = 0; scope = "";
        } else if (parts[0].equals("global") || parts[0].equals("aggregated")) {
            index = 1; scope = parts[0];
        } else if (parts.length >= 3 && Set.of("zones", "regions").contains(parts[0])) {
            index = 2; scope = parts[0] + "/" + parts[1];
        } else { throw GcpException.notFound("Unknown Compute resource path: " + path); }
        if (parts.length <= index || parts.length > index + 3) { throw GcpException.notFound("Invalid resource path"); }
        Context c = new Context(project, scope, parts[index], parts.length > index + 1 ? parts[index + 1] : null,
                parts.length > index + 2 ? parts[index + 2] : null, state);
        if (scope.startsWith("regions/") && !config.services().compute().regions().contains(scope.substring(8))) {
            throw GcpException.notFound("Unknown region: " + scope);
        }
        if (scope.startsWith("zones/")) {
            String zone = scope.substring(6);
            if (config.services().compute().regions().stream().noneMatch(r -> List.of(r + "-a", r + "-b", r + "-c").contains(zone))) {
                throw GcpException.notFound("Unknown zone: " + zone);
            }
        }
        return c;
    }
    private ComputeResourceHandler handler(Context c) {
        return handlers.stream().filter(h -> h.handles(c.collection())).findFirst()
                .orElseThrow(() -> GcpException.unimplemented("Compute collection not implemented: " + c.collection()));
    }
    public synchronized ObjectNode get(String project, String path, Map<String, String> query) {
        Context c = context(project, path, state(project));
        if (c.collection().equals("operations")) {
            if (c.name() != null) { return operation(c).response.deepCopy(); }
            return page(c, c.state.operations.entrySet().stream().filter(e -> e.getKey().startsWith(c.scope() + "/operations/"))
                    .map(e -> e.getValue().response).toList(), query);
        }
        if (ComputeCatalog.COLLECTIONS.contains(c.collection())) {
            List<ObjectNode> values = ComputeCatalog.list(c, config.services().compute().regions());
            if (c.name() == null) { return page(c, values, query); }
            return values.stream().filter(r -> r.path("name").asText().equals(c.name())).findFirst()
                    .orElseThrow(() -> GcpException.notFound("Catalog resource not found"));
        }
        handler(c);
        if (c.collection().equals("images") && "family".equals(c.name()) && c.action != null) {
            return c.state.resources.entrySet().stream().filter(e -> e.getKey().startsWith("global/images/"))
                    .map(Map.Entry::getValue).filter(r -> r.path("family").asText().equals(c.action)
                            && r.path("status").asText().equals("READY") && !r.has("deprecated"))
                    .max(Comparator.comparing(r -> r.path("id").asLong()))
                    .orElseThrow(() -> GcpException.notFound("Image family not found")).deepCopy();
        }
        if (c.action != null) { throw GcpException.unimplemented("Unsupported read action: " + c.action); }
        if (c.name() != null) { return c.require(c.key()).deepCopy(); }
        List<ObjectNode> resources = c.state.resources.entrySet().stream()
                .filter(e -> c.scope().equals("aggregated") ? e.getKey().matches("(?:zones|regions)/[^/]+/" + c.collection() + "/[^/]+")
                        : e.getKey().startsWith(c.scope() + "/" + c.collection() + "/"))
                .map(Map.Entry::getValue).toList();
        ObjectNode result = page(c, resources, query);
        if (c.scope().equals("aggregated")) {
            var grouped = object();
            for (JsonNode resource : result.path("items")) {
                String key = localPath(project, resource.path("selfLink").asText());
                String group = key.substring(0, key.indexOf('/', key.indexOf('/') + 1));
                ObjectNode entry = (ObjectNode) grouped.get(group);
                if (entry == null) { entry = grouped.putObject(group); entry.putArray(c.collection()); }
                entry.withArray(c.collection()).add(resource);
            }
            result.set("items", grouped);
            result.put("kind", "compute#" + singular(c.collection()) + "AggregatedList");
        }
        return result;
    }
    public synchronized ObjectNode mutate(String project, String path, String verb, ObjectNode input, Map<String, String> query) {
        Context c = context(project, path, state(project));
        ObjectNode body = input == null ? object() : input.deepCopy();
        if (c.collection().equals("operations")) {
            ComputeOperation op = operation(c);
            if (verb.equals("POST") && "wait".equals(c.action)) { return op.response.deepCopy(); }
            if (verb.equals("DELETE") && c.action == null) {
                if (!op.response.path("status").asText().equals("DONE")) { throw GcpException.invalidArgument("Operation is not complete"); }
                c.state.operations.remove(c.key());
                save(project, c.state);
                return object();
            }
            throw GcpException.unimplemented("Unsupported operations method");
        }
        ComputeResourceHandler handler = handler(c);
        if (verb.equals("POST") && "listNetworkEndpoints".equals(c.action)) {
            return page(c, toObjects(c.require(c.key()).path("networkEndpoints")), bodyToQuery(body));
        }
        String requestId = query.get("requestId");
        String requestKey = null;
        if (requestId != null) {
            try {
                UUID id = UUID.fromString(requestId);
                if (!id.toString().equalsIgnoreCase(requestId) || id.equals(new UUID(0, 0))) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException e) { throw GcpException.invalidArgument("requestId must be a nonzero UUID"); }
            requestKey = verb + " " + path + " " + requestId;
            String prior = c.state.requests.get(requestKey);
            if (prior != null) {
                ComputeOperation op = c.state.operations.get(prior);
                if (op != null) { return op.response.deepCopy(); }
                throw GcpException.alreadyExists("The request has already completed and its operation was deleted");
            }
        }
        ObjectNode resource;
        String operationType;
        if (verb.equals("POST") && c.name() == null) {
            name(required(body, "name"));
            c = context(project, path + "/" + body.path("name").asText(), c.state);
            if (c.state.resources.containsKey(c.key())) { throw GcpException.alreadyExists("Resource already exists: " + c.key()); }
            resource = body;
            resource.put("id", Long.toString(++c.state.sequence)).put("kind", "compute#" + singular(c.collection()))
                    .put("selfLink", c.link(c.key())).put("creationTimestamp", Instant.now().toString());
            if (c.scope().startsWith("zones/")) { resource.put("zone", c.link(c.scope())); }
            if (c.scope().startsWith("regions/")) { resource.put("region", c.link(c.scope())); }
            if (LABELLED.contains(c.collection())) {
                labels(resource.path("labels")); resource.put("labelFingerprint", fingerprint());
            }
            handler.create(c, resource);
            c.put(c.key(), resource);
            operationType = "insert";
        } else {
            resource = c.require(c.key());
            c.ready(resource);
            if (verb.equals("DELETE") && c.action == null) {
                handler.delete(c, resource);
                c.noReferences(resource.path("selfLink").asText(), c.key());
                c.state.resources.remove(c.key());
                operationType = "delete";
            } else if (verb.equals("POST") && "setLabels".equals(c.action) && LABELLED.contains(c.collection())) {
                checkFingerprint(resource, body, "labelFingerprint");
                labels(body.path("labels"));
                resource.set("labels", body.has("labels") ? body.get("labels") : object());
                resource.put("labelFingerprint", fingerprint());
                operationType = "setLabels";
            } else if (verb.equals("POST") && c.action != null) {
                handler.action(c, resource, c.action, body, query); operationType = c.action;
            } else if ((verb.equals("PATCH") || verb.equals("PUT")) && c.action == null) {
                handler.update(c, resource, body, verb); operationType = "update";
            } else { throw GcpException.unimplemented("Unsupported Compute method"); }
        }
        ComputeOperation op = new ComputeOperation();
        long now = System.currentTimeMillis();
        long delay = Math.max(2, config.services().compute().operationDelayMs());
        op.startedAt = now + delay / 2;
        op.readyAt = now + delay;
        op.finalStates.putAll(c.finalStates);
        String operationName = "operation-" + UUID.randomUUID();
        String operationKey = c.scope() + "/operations/" + operationName;
        op.response = object().put("kind", "compute#operation").put("id", Long.toString(++c.state.sequence))
                .put("name", operationName).put("operationType", operationType).put("targetLink", resource.path("selfLink").asText())
                .put("targetId", resource.path("id").asText()).put("status", "PENDING").put("progress", 0)
                .put("insertTime", Instant.ofEpochMilli(now).toString()).put("selfLink", c.link(operationKey));
        if (c.scope().startsWith("zones/")) { op.response.put("zone", c.link(c.scope())); }
        if (c.scope().startsWith("regions/")) { op.response.put("region", c.link(c.scope())); }
        if (requestId != null) { op.response.put("clientOperationId", requestId); c.state.requests.put(requestKey, operationKey); }
        c.state.operations.put(operationKey, op);
        save(project, c.state);
        return op.response.deepCopy();
    }
    private ComputeOperation operation(Context c) {
        ComputeOperation op = c.state.operations.get(c.key());
        if (op == null) { throw GcpException.notFound("Operation not found: " + c.key()); }
        return op;
    }
    public ObjectNode page(Context c, List<ObjectNode> input, Map<String, String> query) {
        int limit = integer(query.getOrDefault("maxResults", "500"), 0, 500, "maxResults");
        if (limit == 0) { limit = 500; }
        String filter = query.getOrDefault("filter", "");
        Predicate<ObjectNode> predicate = filter(filter);
        String order = query.getOrDefault("orderBy", "name");
        if (!Set.of("name", "creationTimestamp desc").contains(order)) { throw GcpException.invalidArgument("Unsupported orderBy"); }
        Comparator<ObjectNode> comparator = Comparator.comparing(r -> r.path(order.startsWith("creation") ? "creationTimestamp" : "name").asText());
        if (order.endsWith("desc")) { comparator = comparator.reversed(); }
        comparator = comparator.thenComparing(r -> r.path("selfLink").asText());
        List<ObjectNode> rows = input.stream().filter(predicate).sorted(comparator).toList();
        String context = c.project + "/" + c.scope + "/" + c.collection + "|" + filter + "|" + order;
        int offset = 0;
        if (query.containsKey("pageToken") && !query.get("pageToken").isEmpty()) {
            try {
                String token = new String(Base64.getUrlDecoder().decode(query.get("pageToken")), StandardCharsets.UTF_8);
                int split = token.lastIndexOf('\n');
                if (!token.substring(0, split).equals(context)) { throw new IllegalArgumentException(); }
                offset = Integer.parseInt(token.substring(split + 1));
                if (offset < 0 || offset > rows.size()) { throw new IllegalArgumentException(); }
            } catch (RuntimeException e) { throw GcpException.invalidArgument("Invalid pageToken for this request"); }
        }
        int end = Math.min(offset + limit, rows.size());
        ObjectNode response = object().put("kind", "compute#" + singular(c.collection) + "List");
        var items = response.putArray("items");
        rows.subList(offset, end).forEach(r -> items.add(r.deepCopy()));
        if (end < rows.size()) { response.put("nextPageToken", Base64.getUrlEncoder().withoutPadding()
                .encodeToString((context + "\n" + end).getBytes(StandardCharsets.UTF_8))); }
        return response;
    }
    private static Predicate<ObjectNode> filter(String expression) {
        if (expression.isBlank()) { return r -> true; }
        List<Predicate<ObjectNode>> conditions = new ArrayList<>();
        for (String term : expression.split("(?i)\\s+AND\\s+")) {
            var match = Pattern.compile("\\(?\\s*(name|id|status|family|labels\\.[a-z0-9_-]+)\\s*(=|!=|:)\\s*\"?([^\"()]+?)\"?\\s*\\)?").matcher(term.strip());
            if (!match.matches()) { throw GcpException.invalidArgument("Unsupported filter expression: " + term); }
            String field = match.group(1), operator = match.group(2), value = match.group(3).strip();
            conditions.add(r -> {
                JsonNode actual = field.startsWith("labels.") ? r.path("labels").path(field.substring(7)) : r.path(field);
                boolean equal = value.equals("*") ? !actual.isMissingNode() : actual.asText().equals(value);
                return operator.equals("!=") ? !equal : equal;
            });
        }
        return r -> conditions.stream().allMatch(p -> p.test(r));
    }
    static Map<String, String> bodyToQuery(ObjectNode body) {
        Map<String, String> query = new HashMap<>();
        body.properties().forEach(e -> query.put(e.getKey(), e.getValue().asText()));
        return query;
    }
    static List<ObjectNode> toObjects(JsonNode node) {
        List<ObjectNode> result = new ArrayList<>();
        node.forEach(n -> result.add((ObjectNode) n)); return result;
    }
    public static ObjectNode object() { return JSON.createObjectNode(); }
    static String singular(String collection) {
        return switch (collection) {
            case "addresses" -> "address"; case "targetHttpProxies" -> "targetHttpProxy";
            case "urlMaps" -> "urlMap"; default -> collection.substring(0, collection.length() - 1);
        };
    }
    public static String required(ObjectNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) { throw GcpException.invalidArgument("Missing or invalid " + field); }
        return value.asText();
    }
    public static void name(String value) {
        if (!NAME.matcher(value).matches()) { throw GcpException.invalidArgument("Invalid resource name: " + value); }
    }
    public static int integer(String value, int min, int max, String field) {
        try { int n = Integer.parseInt(value); if (n >= min && n <= max) { return n; } }
        catch (NumberFormatException ignored) {}
        throw GcpException.invalidArgument("Invalid " + field);
    }
    public static String fingerprint() { return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)); }
    public static void checkFingerprint(ObjectNode existing, ObjectNode input, String field) {
        if (!existing.path(field).asText().equals(input.path(field).asText()) || !input.hasNonNull(field)) {
            throw GcpException.conditionNotMet("Supplied " + field + " does not match the current resource");
        }
    }
    public static void labels(JsonNode labels) {
        if (labels.isMissingNode()) { return; }
        if (!labels.isObject() || labels.size() > 64) { throw GcpException.invalidArgument("Invalid labels"); }
        labels.properties().forEach(e -> {
            if (!e.getKey().matches("[a-z][a-z0-9_-]{0,62}") || !e.getValue().isTextual()
                    || !e.getValue().asText().matches("[a-z0-9_-]{0,63}")) { throw GcpException.invalidArgument("Invalid label"); }
        });
    }
    static String localPath(String project, String ref) {
        String path = ref;
        int at = path.indexOf("projects/");
        if (at >= 0) {
            path = path.substring(at + 9);
            if (!path.startsWith(project + "/")) { throw GcpException.invalidArgument("Cross-project Compute references are not implemented"); }
            path = path.substring(project.length() + 1);
        }
        if (path.contains("..") || path.contains("?") || path.contains("#") || path.startsWith("/")) { throw GcpException.invalidArgument("Invalid resource reference"); }
        return path;
    }
    public final class Context {
        private final String project, scope, collection, name, action;
        public final ComputeProject state;
        private final Map<String, String> finalStates = new LinkedHashMap<>();
        Context(String project, String scope, String collection, String name, String action, ComputeProject state) {
            this.project = project; this.scope = scope; this.collection = collection; this.name = name; this.action = action; this.state = state;
        }
        public String project() { return project; }
        public String scope() { return scope; }
        public String collection() { return collection; }
        public String name() { return name; }
        public String key() { return (scope.isEmpty() ? "" : scope + "/") + collection + "/" + name; }
        public ObjectNode object() { return ComputeService.object(); }
        public String link(String path) { return "https://www.googleapis.com/compute/v1/projects/" + project + "/" + path; }
        public String path(String ref) { return localPath(project, ref); }
        public ObjectNode require(String ref) {
            String key = path(ref);
            Context target = context(project, key, state);
            if (ComputeCatalog.COLLECTIONS.contains(target.collection)) {
                return ComputeCatalog.list(target, config.services().compute().regions()).stream()
                        .filter(r -> r.path("name").asText().equals(target.name)).findFirst()
                        .orElseThrow(() -> GcpException.notFound("Catalog resource not found: " + ref));
            }
            ObjectNode found = state.resources.get(key);
            if (found == null && target.name != null && target.name.matches("[0-9]+")) {
                found = state.resources.entrySet().stream().filter(e -> e.getKey().startsWith(target.scope + "/" + target.collection + "/")
                        && e.getValue().path("id").asText().equals(target.name)).map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (found == null) { throw GcpException.notFound("Resource not found: " + ref); }
            return found;
        }
        public ObjectNode reference(ObjectNode body, String field, String expectedCollection) {
            String ref = required(body, field);
            String path = path(ref);
            if (!path.contains("/" + expectedCollection + "/")) { throw GcpException.invalidArgument("Invalid " + field + " reference"); }
            ObjectNode result = require(path); ready(result);
            body.put(field, result.path("selfLink").asText());
            return result;
        }
        public void put(String key, ObjectNode value) { state.resources.put(key, value); }
        public void transition(ObjectNode resource, String initial, String terminal) {
            resource.put("status", initial);
            finalStates.put(path(resource.path("selfLink").asText()), terminal);
        }
        public void ready(ObjectNode resource) {
            if (Set.of("PROVISIONING", "STAGING", "STOPPING", "CREATING", "PENDING", "DELETING").contains(resource.path("status").asText())) {
                throw GcpException.failedPrecondition("Resource operation is still in progress");
            }
        }
        public void scope(String expected) {
            if (!(expected.equals("global") ? scope.equals("global") : scope.startsWith(expected + "/"))) {
                throw GcpException.invalidArgument("Invalid scope for " + collection);
            }
        }
        public void noReferences(String target, String except) {
            for (var e : state.resources.entrySet()) {
                if (!e.getKey().equals(except) && references(e.getValue(), target)) {
                    throw GcpException.failedPrecondition("Resource is in use by " + e.getKey());
                }
            }
        }
        private boolean references(JsonNode node, String target) {
            if (node.isTextual()) { return node.asText().equals(target); }
            if (node.isContainerNode()) {
                for (var entry : node.properties()) {
                    if (Set.of("sourceDisk", "sourceImage", "sourceSnapshot").contains(entry.getKey())) { continue; }
                    if (references(entry.getValue(), target)) { return true; }
                }
                if (node.isArray()) { for (JsonNode value : node) { if (references(value, target)) { return true; } } }
            }
            return false;
        }
    }
}
