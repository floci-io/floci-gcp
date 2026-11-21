package io.floci.gcp.testing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadStatesTest {

    @Test
    void awaitStateReturnsTheStateTheThreadReached() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertEquals(Thread.State.WAITING, ThreadStates.awaitParkedOrDone(waiter));
        } finally {
            release.countDown();
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertEquals(Thread.State.TERMINATED, ThreadStates.awaitParkedOrDone(waiter));
    }

    @Test
    void awaitStateFailsNamingTheThreadWhenItNeverGetsThere() {
        Thread unstarted = new Thread(() -> { });

        AssertionError failure = assertThrows(AssertionError.class, () -> ThreadStates.awaitState(
                unstarted, EnumSet.of(Thread.State.WAITING), Duration.ofMillis(20), "the worker"));

        assertTrue(failure.getMessage().startsWith("the worker never reached [WAITING], it was NEW"),
                failure.getMessage());
    }

    @Test
    void awaitBlockedOrDoneFailsWhenTheWorkerNeverStartsOrBlocks() {
        AssertionError failure = assertThrows(AssertionError.class, () -> ThreadStates.awaitBlockedOrDone(
                new AtomicReference<>(), new CompletableFuture<>(), Duration.ofMillis(20)));

        assertTrue(failure.getMessage().contains("not started"), failure.getMessage());
    }

    @Test
    void awaitBlockedFailsWhenTheWorkerFinishesWithoutBlocking() throws Exception {
        Thread finished = Thread.ofPlatform().start(() -> { });
        finished.join(TimeUnit.SECONDS.toMillis(10));

        AssertionError failure = assertThrows(AssertionError.class, () -> ThreadStates.awaitBlocked(
                new AtomicReference<>(finished), Duration.ofMillis(20)));

        assertTrue(failure.getMessage().contains("TERMINATED"), failure.getMessage());
    }

    @Test
    void awaitBlockedReturnsOnceTheWorkerWaits() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            ThreadStates.awaitBlocked(waiter);
        } finally {
            release.countDown();
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }
    }
}
