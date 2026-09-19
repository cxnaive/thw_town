package cn.lunadeer.dominion.cache;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.utils.AutoTimer;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static cn.lunadeer.dominion.cache.DominionNode.getDominionNodeByPosition;

/**
 * The DominionNodeSectored class manages the dominion nodes in different sectors of the world
 * with thread-safe operations.
 */
public class DominionNodeSectored {
    /*
        D | C
        --+--
        B | A
     */

    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * @param sectorA x >= originX, z >= originZ
     * @param sectorB x <= originX, z >= originZ
     * @param sectorC x >= originX, z <= originZ
     * @param sectorD x <= originX, z <= originZ
     */
    private record Snapshot(ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> sectorA,
                            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> sectorB,
                            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> sectorC,
                            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> sectorD, int originX,
                            int originZ) {

        private static Snapshot empty() {
                return new Snapshot(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                        new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), 0, 0);
            }
        }

    /**
     * Gets the DominionDTO for a given location.
     *
     * @param loc the location to check
     * @return the DominionDTO if found, otherwise null
     */
    public DominionDTO getDominionByLocation(@NotNull Location loc) {
        return getDominionByPosition(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public DominionDTO getDominionByPosition(UUID world, int x, int y, int z) {
        try (AutoTimer ignored = new AutoTimer(Configuration.timer)) {
            CopyOnWriteArrayList<DominionNode> nodes = getNodes(world, x, z);
            if (nodes == null) return null;
            if (nodes.isEmpty()) return null;
            DominionNode dominionNode = getDominionNodeByPosition(nodes, world, x, y, z);
            return dominionNode == null ? null : dominionNode.getDominion();
        }
    }

    /**
     * Gets the list of DominionNodes for a given location.
     *
     * @param loc the location to check
     * @return the list of DominionNodes
     */
    public CopyOnWriteArrayList<DominionNode> getNodes(@NotNull Location loc) {
        return getNodes(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockZ());
    }

    /**
     * Gets the list of DominionNodes for a given world and coordinates.
     *
     * @param world the world to check
     * @param x     the x-coordinate
     * @param z     the z-coordinate
     * @return the list of DominionNodes
     */
    public CopyOnWriteArrayList<DominionNode> getNodes(World world, int x, int z) {
        return getNodes(world.getUID(), x, z);
    }

    /**
     * Gets the list of DominionNodes for a given world UUID and coordinates.
     *
     * @param world the world UUID to check
     * @param x     the x-coordinate
     * @param z     the z-coordinate
     * @return the list of DominionNodes
     */
    public CopyOnWriteArrayList<DominionNode> getNodes(UUID world, int x, int z) {
        Snapshot current = snapshot;

        if (x >= current.originX && z >= current.originZ) {
            return current.sectorA.get(world);
        }
        if (x <= current.originX && z >= current.originZ) {
            return current.sectorB.get(world);
        }
        if (x >= current.originX) {
            return current.sectorC.get(world);
        }
        return current.sectorD.get(world);
    }

    /** Build before publishing the owning cache snapshot. No background generation may overtake it. */
    public synchronized void build(CopyOnWriteArrayList<DominionNode> nodes) {
        try (AutoTimer ignored = new AutoTimer(Configuration.timer)) {
            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> a = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> b = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> c = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> d = new ConcurrentHashMap<>();
            int maxX = nodes.stream().mapToInt(n -> n.getDominion().getCuboid().x2()).max().orElse(0);
            int minX = nodes.stream().mapToInt(n -> n.getDominion().getCuboid().x1()).min().orElse(0);
            int maxZ = nodes.stream().mapToInt(n -> n.getDominion().getCuboid().z2()).max().orElse(0);
            int minZ = nodes.stream().mapToInt(n -> n.getDominion().getCuboid().z1()).min().orElse(0);
            int originX = (int) (((long) maxX + minX) / 2);
            int originZ = (int) (((long) maxZ + minZ) / 2);
            for (DominionNode node : nodes) {
                UUID world = node.getDominion().getWorldUid();
                a.computeIfAbsent(world, key -> new CopyOnWriteArrayList<>());
                b.computeIfAbsent(world, key -> new CopyOnWriteArrayList<>());
                c.computeIfAbsent(world, key -> new CopyOnWriteArrayList<>());
                d.computeIfAbsent(world, key -> new CopyOnWriteArrayList<>());
                placeDominionInSectors(node, node.getDominion(), originX, originZ, a, b, c, d);
            }
            snapshot = new Snapshot(a, b, c, d, originX, originZ);
        }
    }

    /** Compatibility entry; internal cache publication uses build directly. */
    public CompletableFuture<Void> buildAsync(CopyOnWriteArrayList<DominionNode> nodes) {
        return CompletableFuture.runAsync(() -> build(nodes));
    }

    /**
     * Helper method to place a dominion node into the appropriate sectors.
     */
    private void placeDominionInSectors(DominionNode n, DominionDTO d, int tempOriginX, int tempOriginZ,
                                        ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> tempSectorA,
                                        ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> tempSectorB,
                                        ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> tempSectorC,
                                        ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> tempSectorD) {
        if (d.getCuboid().x1() >= tempOriginX && d.getCuboid().z1() >= tempOriginZ) {
            tempSectorA.get(d.getWorldUid()).add(n);
        } else if (d.getCuboid().x1() <= tempOriginX && d.getCuboid().z1() >= tempOriginZ) {
            if (d.getCuboid().x2() >= tempOriginX) {
                tempSectorA.get(d.getWorldUid()).add(n);
                tempSectorB.get(d.getWorldUid()).add(n);
            } else {
                tempSectorB.get(d.getWorldUid()).add(n);
            }
        } else if (d.getCuboid().x1() >= tempOriginX && d.getCuboid().z1() <= tempOriginZ) {
            if (d.getCuboid().z2() >= tempOriginZ) {
                tempSectorA.get(d.getWorldUid()).add(n);
                tempSectorC.get(d.getWorldUid()).add(n);
            } else {
                tempSectorC.get(d.getWorldUid()).add(n);
            }
        } else {
            if (d.getCuboid().x2() >= tempOriginX && d.getCuboid().z2() >= tempOriginZ) {
                tempSectorA.get(d.getWorldUid()).add(n);
                tempSectorB.get(d.getWorldUid()).add(n);
                tempSectorC.get(d.getWorldUid()).add(n);
                tempSectorD.get(d.getWorldUid()).add(n);
            } else if (d.getCuboid().x2() >= tempOriginX && d.getCuboid().z2() <= tempOriginZ) {
                tempSectorC.get(d.getWorldUid()).add(n);
                tempSectorD.get(d.getWorldUid()).add(n);
            } else if (d.getCuboid().z2() >= tempOriginZ && d.getCuboid().x2() <= tempOriginX) {
                tempSectorB.get(d.getWorldUid()).add(n);
                tempSectorD.get(d.getWorldUid()).add(n);
            } else {
                tempSectorD.get(d.getWorldUid()).add(n);
            }
        }
    }
}
