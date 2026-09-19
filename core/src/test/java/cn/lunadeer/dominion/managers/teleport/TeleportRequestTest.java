package cn.lunadeer.dominion.managers.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.lunadeer.dominion.managers.teleport.TeleportRequest.State.*;
import static org.junit.jupiter.api.Assertions.*;

class TeleportRequestTest {
    private TeleportRequest request() {
        return new TeleportRequest(UUID.randomUUID(), UUID.randomUUID(), 42, false,
                new TeleportRequest.Origin(UUID.randomUUID(), -1, 80, 100));
    }

    @Test
    void positionGuardIncludesWorldEvenWhenCoordinatesAreIdentical() {
        var request = request();
        assertTrue(request.origin.matches(request.origin.world(), -1, 80, 100));
        assertFalse(request.origin.matches(UUID.randomUUID(), -1, 80, 100));
        assertFalse(request.origin.matches(request.origin.world(), 0, 80, 100));
    }

    @Test
    void cancelledRequestRejectsLateExecutionAndClosesLateResource() {
        var request = request();
        assertTrue(request.transition(WAITING, PREPARING));
        assertTrue(request.finish(CANCELLED, false));
        AtomicInteger released = new AtomicInteger();
        request.resource(released::incrementAndGet);
        assertEquals(1, released.get());
        assertFalse(request.transition(PREPARING, EXECUTING));
        assertFalse(request.finish(SUCCEEDED, false));
    }

    @Test
    void executingRequestCannotBeCancelledOrReplacedUntilResultArrives() {
        var request = request();
        request.transition(WAITING, PREPARING);
        request.transition(PREPARING, EXECUTING);
        assertFalse(request.finish(CANCELLED, false));
        assertEquals(EXECUTING, request.state());
        assertTrue(request.finish(FAILED, false));
        assertFalse(request.finish(SUCCEEDED, false));
    }

    @Test
    void retiredSessionCanReleaseExecutingRequest() {
        var request = request();
        request.transition(WAITING, PREPARING);
        request.transition(PREPARING, EXECUTING);
        assertTrue(request.finish(CANCELLED, true));
    }

    @Test
    void resourceReplacementAndTerminalCleanupAreExactlyOnce() {
        var request = request();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger tasks = new AtomicInteger();
        request.resource(released::incrementAndGet);
        request.resource(released::incrementAndGet);
        request.cleanup(tasks::incrementAndGet);
        assertEquals(1, released.get());
        request.finish(FAILED, false);
        request.finish(CANCELLED, true);
        assertEquals(2, released.get());
        assertEquals(1, tasks.get());
        request.cleanup(tasks::incrementAndGet);
        assertEquals(2, tasks.get());
    }

    @Test
    void onlyOneTerminalCallbackWins() throws Exception {
        var request = request();
        AtomicInteger winners = new AtomicInteger(), released = new AtomicInteger();
        request.resource(released::incrementAndGet);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 40; i++) pool.submit(() -> {
                try { start.await(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                if (request.finish(CANCELLED, false)) winners.incrementAndGet();
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, winners.get());
            assertEquals(1, released.get());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void oldCallbackCannotRemoveNewRequestOrReconnectedSession() {
        var old = request();
        var next = request();
        var requests = new ConcurrentHashMap<UUID, TeleportRequest>();
        UUID player = UUID.randomUUID();
        requests.put(player, old);
        old.finish(CANCELLED, false);
        requests.put(player, next);
        assertFalse(requests.remove(player, old));
        assertSame(next, requests.get(player));
        assertNotEquals(old.session, next.session);
    }
}
