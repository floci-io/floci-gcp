package io.floci.gcp.services.billingbudgets;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.billingbudgets.model.BudgetRecord;
import io.floci.gcp.testing.ThreadStates;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingBudgetsServiceTest {

    private static final String ACCOUNT = "0000AA-999999-000001";

    @Test
    void deleteDuringAnUpdateIsNotUndoneByTheUpdate() throws InterruptedException {
        AtomicReference<Runnable> onRead = new AtomicReference<>();
        InMemoryStorage<String, BudgetRecord> store = new InMemoryStorage<>() {
            @Override
            public Optional<BudgetRecord> get(String key) {
                Optional<BudgetRecord> read = super.get(key);
                Runnable hook = onRead.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
                return read;
            }
        };
        BillingBudgetsService service = new BillingBudgetsService(store, null, null);
        String name = (String) service.createBudget(ACCOUNT, Map.of(
                "amount", Map.of("lastPeriodAmount", Map.of()))).get("name");
        String budgetId = name.substring(name.lastIndexOf('/') + 1);

        AtomicReference<Thread> deleter = new AtomicReference<>();
        onRead.set(() -> {
            Thread thread = new Thread(() -> service.deleteBudget(ACCOUNT, budgetId));
            deleter.set(thread);
            thread.start();
            try {
                ThreadStates.awaitBlockedOrDone(thread);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        service.updateBudget(ACCOUNT, budgetId, "displayName", Map.of("displayName", "after"));
        deleter.get().join(Duration.ofSeconds(10).toMillis());

        assertEquals(Thread.State.TERMINATED, deleter.get().getState());
        GcpException notFound = assertThrows(GcpException.class, () -> service.getBudget(ACCOUNT, budgetId));
        assertTrue(notFound.getMessage().contains("not found"));
    }

    @Test
    void nestedMaskPathUpdatesTheNestedField() {
        BillingBudgetsService service = new BillingBudgetsService(new InMemoryStorage<>(), null, null);
        Map<String, Object> customPeriod = Map.of("startDate", Map.of("year", 2026, "month", 1, "day", 1));
        String name = (String) service.createBudget(ACCOUNT, Map.of(
                "amount", Map.of("specifiedAmount", Map.of("currencyCode", "USD", "units", "5")),
                "budgetFilter", Map.of("customPeriod", customPeriod, "projects", List.of("projects/p"))))
                .get("name");
        String budgetId = name.substring(name.lastIndexOf('/') + 1);

        Map<String, Object> updated = service.updateBudget(ACCOUNT, budgetId, "budgetFilter.customPeriod.startDate",
                Map.of("budgetFilter", Map.of("customPeriod",
                        Map.of("startDate", Map.of("year", 2026, "month", 6, "day", 1)))));

        Map<?, ?> filter = (Map<?, ?>) updated.get("budgetFilter");
        Map<?, ?> period = (Map<?, ?>) filter.get("customPeriod");
        assertEquals(Map.of("year", 2026, "month", 6, "day", 1), period.get("startDate"));
        assertEquals(List.of("projects/p"), filter.get("projects"));
        assertEquals(null, filter.get("customPeriod.startDate"));
    }
}
