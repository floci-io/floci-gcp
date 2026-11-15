package io.floci.gcp.services.billingbudgets;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.billingbudgets.model.BudgetRecord;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Cloud Billing Budget API v1 ({@code billingbudgets.googleapis.com}): budget CRUD under a billing
 * account. Budgets are stored; spend is not tracked, so no threshold notification is ever sent.
 */
@ApplicationScoped
public class BillingBudgetsService {

    private static final Logger LOG = Logger.getLogger(BillingBudgetsService.class);

    private static final Pattern BILLING_ACCOUNT_ID = Pattern.compile("[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}");

    private static final Pattern BUDGET_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Cloud Billing reports every emulated billing account in this currency. */
    static final String ACCOUNT_CURRENCY_CODE = "USD";

    /** budget_service.proto: "The default and maximum value are 100." */
    static final int MAX_PAGE_SIZE = 100;

    private static final int MAX_DISPLAY_NAME_LENGTH = 60;

    private static final String INVALID_ARGUMENT = "Request contains an invalid argument.";

    private static final String NOT_FOUND = "Requested entity was not found.";

    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "display_name", "budget_filter", "amount", "threshold_rules", "notifications_rule");

    /** Accepted in an update mask but never changed, as in GCP. */
    private static final Set<String> IGNORED_MASK_FIELDS = Set.of("name", "etag");

    private final StorageBackend<String, BudgetRecord> budgets;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final AtomicLong lastEtag = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();

    @Inject
    public BillingBudgetsService(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                                 EmulatorConfig config) {
        this(storageFactory.createGlobal("billingbudgets", "billingbudgets.json",
                new TypeReference<Map<String, BudgetRecord>>() {}), serviceRegistry, config);
    }

    BillingBudgetsService(StorageBackend<String, BudgetRecord> budgets, ServiceRegistry serviceRegistry,
                          EmulatorConfig config) {
        this.budgets = budgets;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        for (BudgetRecord stored : budgets.scan(key -> true)) {
            sequence.accumulateAndGet(stored.getSequence(), Math::max);
        }
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("billingbudgets")
                .enabled(config.services().billingbudgets().enabled())
                .storageKey("billingbudgets")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(BillingBudgetsController.class)
                .api("billingbudgets.googleapis.com", "Cloud Billing Budget API")
                .build());
    }

    public Map<String, Object> createBudget(String account, Map<String, Object> body) {
        String parent = requireBillingAccount(account);
        Map<String, Object> budget = new LinkedHashMap<>(body != null ? body : Map.of());
        budget.remove("name");
        budget.remove("etag");
        Map<String, Object> stored = validateAndFillDefaults(budget);
        String name = parent + "/budgets/" + UUID.randomUUID();
        Map<String, Object> created = withNameAndEtag(name, stored);
        budgets.put(name, new BudgetRecord(sequence.incrementAndGet(), created));
        LOG.infof("create budget %s", name);
        return created;
    }

    public Map<String, Object> getBudget(String account, String budgetId) {
        return find(account, budgetId).getBudget();
    }

    public Map<String, Object> listBudgets(String account, int pageSize, String pageToken, String scope) {
        String parent = requireBillingAccount(account) + "/budgets/";
        List<BudgetRecord> matching = new ArrayList<>();
        for (BudgetRecord stored : budgets.scan(key -> key.startsWith(parent))) {
            if (scope == null || scope.isEmpty() || tracksProject(stored.getBudget(), scope)) {
                matching.add(stored);
            }
        }
        matching.sort(Comparator.comparingLong(BudgetRecord::getSequence));
        int offset = decodePageToken(pageToken);
        int size = pageSize > 0 ? Math.min(pageSize, MAX_PAGE_SIZE) : MAX_PAGE_SIZE;
        Map<String, Object> response = new LinkedHashMap<>();
        if (offset >= matching.size()) {
            return response;
        }
        int end = Math.min(offset + size, matching.size());
        List<Map<String, Object>> page = new ArrayList<>();
        for (BudgetRecord stored : matching.subList(offset, end)) {
            page.add(stored.getBudget());
        }
        response.put("budgets", page);
        if (end < matching.size()) {
            response.put("nextPageToken", encodePageToken(end));
        }
        return response;
    }

    public Map<String, Object> updateBudget(String account, String budgetId, String updateMask,
                                            Map<String, Object> body) {
        Map<String, Object> request = body != null ? body : Map.of();
        List<String> mask = parseUpdateMask(updateMask);
        synchronized (this) {
            BudgetRecord existing = find(account, budgetId);
            Map<String, Object> merged = new LinkedHashMap<>(existing.getBudget());
            merged.remove("name");
            merged.remove("etag");
            if (mask.isEmpty()) {
                for (String field : List.of("displayName", "budgetFilter", "amount", "thresholdRules",
                        "notificationsRule")) {
                    if (hasValue(request.get(field))) {
                        merged.put(field, request.get(field));
                    }
                }
            } else {
                for (String path : mask) {
                    applyMaskPath(merged, request, path);
                }
            }
            String name = (String) existing.getBudget().get("name");
            Map<String, Object> updated = withNameAndEtag(name, validateAndFillDefaults(merged));
            budgets.put(name, new BudgetRecord(existing.getSequence(), updated));
            LOG.infof("update budget %s", name);
            return updated;
        }
    }

    public Map<String, Object> deleteBudget(String account, String budgetId) {
        String name = requireBillingAccount(account) + "/budgets/" + budgetId;
        // Same monitor as updateBudget, so an update that read the budget cannot write it back
        // after this delete.
        synchronized (this) {
            budgets.delete(name);
        }
        LOG.infof("delete budget %s", name);
        return Map.of();
    }

    private BudgetRecord find(String account, String budgetId) {
        String parent = requireBillingAccount(account);
        if (!BUDGET_ID.matcher(budgetId).matches()) {
            throw GcpException.notFound(NOT_FOUND);
        }
        return budgets.get(parent + "/budgets/" + budgetId)
                .orElseThrow(() -> GcpException.notFound(NOT_FOUND));
    }

    private static String requireBillingAccount(String account) {
        if (!BILLING_ACCOUNT_ID.matcher(account).matches()) {
            throw GcpException.invalidArgument("Invalid billing account name: billingAccounts/" + account);
        }
        return "billingAccounts/" + account;
    }

    private static Map<String, Object> validateAndFillDefaults(Map<String, Object> budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object displayName = budget.get("displayName");
        if (displayName instanceof String text && !text.isEmpty()) {
            if (text.length() > MAX_DISPLAY_NAME_LENGTH) {
                throw GcpException.invalidArgument("Budget display name " + text
                        + " exceeds length limit " + MAX_DISPLAY_NAME_LENGTH);
            }
            result.put("displayName", text);
        }

        Map<String, Object> filter = new LinkedHashMap<>(asMap(budget.get("budgetFilter")));
        boolean customPeriod = filter.containsKey("customPeriod");
        if (customPeriod && filter.containsKey("calendarPeriod")) {
            throw GcpException.invalidArgument("Invalid value at 'budget.budget_filter' (oneof), oneof field"
                    + " 'usage_period' is already set. Cannot set 'customPeriod'");
        }
        filter.putIfAbsent("creditTypesTreatment", "INCLUDE_ALL_CREDITS");
        if (!customPeriod) {
            filter.putIfAbsent("calendarPeriod", "MONTH");
        }
        result.put("budgetFilter", filter);

        Map<String, Object> amount = asMap(budget.get("amount"));
        boolean specified = amount.containsKey("specifiedAmount");
        boolean lastPeriod = amount.containsKey("lastPeriodAmount");
        if (specified && lastPeriod) {
            throw GcpException.invalidArgument("Invalid value at 'budget.amount' (oneof), oneof field"
                    + " 'budget_amount' is already set. Cannot set 'lastPeriodAmount'");
        }
        if (!specified && !lastPeriod) {
            throw GcpException.invalidArgument(INVALID_ARGUMENT);
        }
        if (specified) {
            Object currency = asMap(amount.get("specifiedAmount")).get("currencyCode");
            if (currency != null && !ACCOUNT_CURRENCY_CODE.equals(currency)) {
                throw GcpException.invalidArgument(INVALID_ARGUMENT);
            }
        } else if (customPeriod) {
            throw GcpException.invalidArgument("Last Period Amount cannot be set with Custom Period.");
        }
        result.put("amount", amount);

        Object rawRules = budget.get("thresholdRules");
        if (rawRules instanceof List<?> rules && !rules.isEmpty()) {
            List<Map<String, Object>> thresholds = new ArrayList<>();
            for (Object rawRule : rules) {
                Map<String, Object> rule = new LinkedHashMap<>(asMap(rawRule));
                double percent = rule.get("thresholdPercent") instanceof Number number ? number.doubleValue() : 0;
                if (percent < 0) {
                    throw GcpException.invalidArgument("Threshold percent must be non-negative: " + percent);
                }
                rule.putIfAbsent("spendBasis", "CURRENT_SPEND");
                if (customPeriod && "FORECASTED_SPEND".equals(rule.get("spendBasis"))) {
                    throw GcpException.invalidArgument(
                            "Threshold percent based on forecast spend cannot be set with Custom Period.");
                }
                thresholds.add(rule);
            }
            result.put("thresholdRules", thresholds);
        }

        Map<String, Object> notifications = new LinkedHashMap<>(asMap(budget.get("notificationsRule")));
        Object topic = notifications.get("pubsubTopic");
        if (topic instanceof String text && !text.isEmpty()) {
            Object schema = notifications.get("schemaVersion");
            if (!"1.0".equals(schema)) {
                throw GcpException.invalidArgument("Invalid schema version: " + (schema != null ? schema : ""));
            }
        }
        result.put("notificationsRule", notifications);
        return result;
    }

    private Map<String, Object> withNameAndEtag(String name, Map<String, Object> budget) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.putAll(budget);
        result.put("etag", Long.toString(nextEtag()));
        return result;
    }

    /** Microseconds since the epoch, strictly increasing, like the etags GCP returns. */
    private long nextEtag() {
        long now = System.currentTimeMillis() * 1000;
        return lastEtag.updateAndGet(previous -> Math.max(previous + 1, now));
    }

    private static List<String> parseUpdateMask(String updateMask) {
        List<String> paths = new ArrayList<>();
        if (updateMask == null || updateMask.isBlank()) {
            return paths;
        }
        for (String raw : updateMask.split(",")) {
            String path = toSnakeCase(raw.trim());
            String top = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            boolean nested = path.contains(".");
            boolean known = IGNORED_MASK_FIELDS.contains(path) || UPDATABLE_FIELDS.contains(top)
                    && (!nested || top.equals("budget_filter") || top.equals("notifications_rule"));
            if (!known) {
                throw GcpException.invalidArgument("Invalid update mask: " + path);
            }
            paths.add(path);
        }
        return paths;
    }

    private static void applyMaskPath(Map<String, Object> budget, Map<String, Object> request, String path) {
        if (IGNORED_MASK_FIELDS.contains(path)) {
            return;
        }
        String[] segments = path.split("\\.");
        setPath(budget, request, segments, 0);
    }

    private static void setPath(Map<String, Object> target, Map<String, Object> source, String[] segments,
                                int index) {
        String field = toCamelCase(segments[index]);
        if (index == segments.length - 1) {
            setOrRemove(target, field, source.get(field));
            return;
        }
        Map<String, Object> child = new LinkedHashMap<>(asMap(target.get(field)));
        setPath(child, asMap(source.get(field)), segments, index + 1);
        target.put(field, child);
    }

    private static void setOrRemove(Map<String, Object> target, String field, Object value) {
        if (value == null) {
            target.remove(field);
        } else {
            target.put(field, value);
        }
    }

    private static boolean tracksProject(Map<String, Object> budget, String scope) {
        Object projects = asMap(budget.get("budgetFilter")).get("projects");
        return projects instanceof List<?> list && list.contains(scope);
    }

    private static int decodePageToken(String pageToken) {
        if (pageToken == null || pageToken.isEmpty()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(new String(Base64.getDecoder().decode(pageToken), StandardCharsets.UTF_8));
            if (offset < 0) {
                throw GcpException.invalidArgument(INVALID_ARGUMENT);
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw GcpException.invalidArgument(INVALID_ARGUMENT);
        }
    }

    private static String encodePageToken(int offset) {
        return Base64.getEncoder().encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasValue(Object value) {
        return switch (value) {
            case null -> false;
            case String text -> !text.isEmpty();
            case List<?> list -> !list.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String toSnakeCase(String path) {
        StringBuilder out = new StringBuilder();
        for (char c : path.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static String toCamelCase(String snake) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
