package cn.lunadeer.dominion.cache.server;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.DominionNode;
import cn.lunadeer.dominion.doos.DominionDOO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DominionCacheSnapshotTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);

    @Test
    void retainedTreeStillResolvesAfterDeletionAndReparenting() {
        DominionCache cache = new DominionCache(1);
        var parent = land(1, -1, 0, 100);
        var child = land(2, 1, 10, 20);
        cache.replaceAll(List.of(parent, child));
        var oldTree = cache.getPlayerDominionNodes(OWNER);
        assertEquals(2, cache.getDominion(WORLD, 15, 15, 15).getId());
        cache.replaceAll(List.of(land(2, -1, 10, 20)));
        assertNull(cache.getDominion(1));
        assertNull(cache.getDominion(WORLD, 50, 50, 50));
        assertEquals(2, cache.getDominion(WORLD, 15, 15, 15).getId());
        assertEquals(1, DominionNode.getDominionNodeByPosition(oldTree, WORLD, 50, 50, 50).getDominionId());
        assertEquals(2, DominionNode.getDominionNodeByPosition(oldTree, WORLD, 15, 15, 15).getDominionId());
        assertEquals(List.of(2), cache.getChildrenOf(-1).stream().map(DominionDTO::getId).toList());
        assertTrue(cache.getChildrenOf(1).isEmpty());
    }

    @Test
    void delayedIndexBuildKeepsOldDataPublishedUntilAllIndexesAreReady() throws Exception {
        DominionCache cache = new DominionCache(1);
        cache.replaceAll(List.of(land(1, -1, 0, 20)));
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var next = land(2, -1, 0, 20);
        DominionDTO delayed = (DominionDTO) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DominionDTO.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCuboid")) {
                        building.countDown();
                        if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test build timed out");
                    }
                    return method.invoke(next, args);
                });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var update = executor.submit(() -> cache.replaceAll(List.of(delayed)));
            assertTrue(building.await(5, TimeUnit.SECONDS));
            assertEquals(1, cache.getDominion(WORLD, 10, 10, 10).getId());
            assertNotNull(cache.getDominion(1));
            assertNull(cache.getDominion(2));
            finish.countDown();
            update.get(5, TimeUnit.SECONDS);
            assertEquals(2, cache.getDominion(WORLD, 10, 10, 10).getId());
            assertNull(cache.getDominion(1));
            assertNotNull(cache.getDominion(2));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedReplacementNeverExposesHalfBuiltOrMissingProtection() throws Exception {
        DominionCache cache = new DominionCache(1);
        List<DominionDTO> before = List.of(land(1, -1, 0, 100), land(2, 1, 10, 20));
        List<DominionDTO> after = List.of(land(3, -1, 0, 100));
        cache.replaceAll(before);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10000; i++) {
                    DominionDTO found = cache.getDominion(WORLD, 15, 15, 15);
                    assertNotNull(found);
                    assertTrue(found.getId() == 2 || found.getId() == 3);
                }
                return null;
            });
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) cache.replaceAll(i % 2 == 0 ? after : before);
                return null;
            });
            start.countDown();
            reader.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void sectorsRespectWorldAndBoundaryCoordinates() {
        DominionCache cache = new DominionCache(1);
        cache.replaceAll(List.of(land(1, -1, -20, 20)));
        for (int x : List.of(-19, 0, 19)) for (int z : List.of(-19, 0, 19)) {
            assertEquals(1, cache.getDominion(WORLD, x, 0, z).getId());
        }
        assertNull(cache.getDominion(new UUID(0, 100), 0, 0, 0));
        assertNull(cache.getDominion(WORLD, 21, 0, 0));
    }

    @Test
    void failedIndexBuildDoesNotPublishAnyPartialData() {
        DominionCache cache = new DominionCache(1);
        cache.replaceAll(List.of(land(1, -1, 0, 100)));
        var next = land(2, -1, 0, 100);
        DominionDTO broken = (DominionDTO) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DominionDTO.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCuboid")) throw new IllegalStateException("Simulated build failure");
                    return method.invoke(next, args);
                });
        assertThrows(IllegalStateException.class, () -> cache.replaceAll(List.of(broken)));
        assertNotNull(cache.getDominion(1));
        assertNull(cache.getDominion(2));
        assertEquals(1, cache.getDominion(WORLD, 50, 50, 50).getId());
        assertEquals(List.of("land1"), cache.getAllDominionNames());
        assertEquals(List.of(1), cache.getPlayerOwnDominionDTOs(OWNER).stream().map(DominionDTO::getId).toList());
    }

    private DominionDOO land(int id, int parent, int low, int high) {
        return new DominionDOO(OWNER, "land" + id, WORLD,
                new CuboidDTO(low, low, low, high, high, high), parent).setId(id);
    }
}
