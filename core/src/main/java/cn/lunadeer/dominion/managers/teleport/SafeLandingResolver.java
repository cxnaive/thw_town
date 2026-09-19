package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static cn.lunadeer.dominion.managers.teleport.TeleportTarget.Point;

/** Region scheduling and bounded selection; terrain rules below are independently testable. */
public final class SafeLandingResolver {
    private static final int COLUMNS_PER_BATCH = 8;
    private static final int LOCAL_VERTICAL_RADIUS = 4;
    private static final int OPEN_HEIGHT = 3;
    private static final int NETHER_OPEN_HEIGHT = 6;
    private static final Set<String> HAZARDS = Set.of("LAVA", "WATER", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK",
            "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS", "SWEET_BERRY_BUSH", "WITHER_ROSE", "POWDER_SNOW",
            "COBWEB", "END_PORTAL", "NETHER_PORTAL", "END_GATEWAY", "POINTED_DRIPSTONE");
    private final TeleportPlatform platform;

    public SafeLandingResolver(TeleportPlatform platform) { this.platform = platform; }

    enum Rule { EXPLICIT, NEARBY, OUTDOOR, NETHER }
    enum Fit { BLOCKED, GROUNDED, AIR }

    /** Air is intentional, never an unvalidated fallback. Keep its policy during the final recheck. */
    public record Landing(Point point, boolean airborne, Rule rule) { }

    public CompletableFuture<Landing> resolve(World world, TeleportTarget target, TeleportRequest request,
                                              int radius, int budget) {
        return new Search(world, target, request, Math.max(0, Math.min(32, radius)),
                Math.max(1, Math.min(65536, budget))).nextChunk();
    }

    private final class Search {
        final World world;
        final TeleportTarget target;
        final TeleportRequest request;
        final List<Column> columns;
        final Selection selection;
        final Budget budget;
        int columnIndex;
        boolean exactChecked;
        TeleportPlatform.ChunkLease lease;

        Search(World world, TeleportTarget target, TeleportRequest request, int radius, int budget) {
            this.world = world;
            this.target = target;
            this.request = request;
            this.budget = new Budget(budget);
            selection = new Selection(target.point(), !target.terrain());
            Map<Column, List<Column>> chunks = new LinkedHashMap<>();
            for (Column column : columns(target.point(), radius)) {
                chunks.computeIfAbsent(new Column(column.x >> 4, column.z >> 4), ignored -> new ArrayList<>()).add(column);
            }
            columns = chunks.values().stream().flatMap(List::stream).toList();
        }

        CompletableFuture<Landing> nextChunk() {
            if (!request.preparing()) return CompletableFuture.failedFuture(new CancellationException());
            if (budget.remaining <= 0 || columnIndex >= columns.size()) return finish();
            Column column = columns.get(columnIndex);
            return platform.prepare(world, column.x >> 4, column.z >> 4, request::preparing).thenCompose(prepared -> {
                lease = prepared;
                request.resource(prepared);
                return batch(column.x >> 4, column.z >> 4);
            });
        }

        CompletableFuture<Landing> batch(int chunkX, int chunkZ) {
            return platform.region(world, chunkX, chunkZ, () -> {
                if (!request.preparing()) throw new CancellationException();
                Terrain terrain = terrain(world, target);
                if (!exactChecked) {
                    exactChecked = true;
                    if (!target.terrain()) {
                        Landing exact = probe(terrain, target, target.point(), Rule.EXPLICIT, !request.internal, budget);
                        // An explicitly selected, clear air position is also authoritative.
                        if (exact != null) return exact;
                    }
                }
                for (int count = 0; count < COLUMNS_PER_BATCH && budget.remaining > 0 && columnIndex < columns.size(); count++) {
                    Column column = columns.get(columnIndex);
                    if ((column.x >> 4) != chunkX || (column.z >> 4) != chunkZ) break;
                    columnIndex++;
                    inspectColumn(terrain, target, column, !request.internal, budget, selection);
                }
                return null;
            }).thenCompose(exact -> {
                if (exact != null) return CompletableFuture.completedFuture(exact);
                if (budget.remaining > 0 && columnIndex < columns.size()) {
                    Column next = columns.get(columnIndex);
                    if ((next.x >> 4) == chunkX && (next.z >> 4) == chunkZ) return batch(chunkX, chunkZ);
                    lease.close();
                    return nextChunk();
                }
                return finish();
            });
        }

        CompletableFuture<Landing> finish() {
            Landing chosen = selection.result();
            if (chosen == null) return CompletableFuture.completedFuture(null);
            // Selection compares all chunks. Reacquire the winner, which may no longer be the last chunk visited.
            return platform.prepare(world, chosen.point.blockX() >> 4, chosen.point.blockZ() >> 4, request::preparing)
                    .thenApply(prepared -> {
                        request.resource(prepared);
                        return chosen;
                    });
        }
    }

    static final class Budget {
        int remaining;
        Budget(int remaining) { this.remaining = remaining; }
        boolean take() {
            if (remaining <= 0) return false;
            remaining--;
            return true;
        }
    }

    /** Chunk order must not decide the destination. Supported positions always outrank automatic air fallback. */
    static final class Selection {
        private final Point origin;
        private final boolean includeHeight;
        private Landing ground;
        private Landing air;

        Selection(Point origin, boolean includeHeight) { this.origin = origin; this.includeHeight = includeHeight; }
        void offer(Landing candidate) {
            if (candidate == null) return;
            if (candidate.airborne) air = nearer(air, candidate);
            else ground = nearer(ground, candidate);
        }
        Landing result() { return ground != null ? ground : air; }
        private Landing nearer(Landing previous, Landing next) {
            if (previous == null) return next;
            int order = Double.compare(distance(next.point), distance(previous.point));
            if (order == 0) order = Double.compare(next.point.y(), previous.point.y());
            if (order == 0) order = Double.compare(next.point.x(), previous.point.x());
            if (order == 0) order = Double.compare(next.point.z(), previous.point.z());
            return order < 0 ? next : previous;
        }
        private double distance(Point point) {
            double dx = point.x() - origin.x(), dz = point.z() - origin.z();
            double dy = includeHeight ? point.y() - origin.y() : 0;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    record Column(int x, int z) { }

    static List<Column> columns(Point origin, int radius) {
        List<Column> result = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) result.add(new Column(origin.blockX() + x, origin.blockZ() + z));
        }
        result.sort(Comparator.comparingLong(column -> {
            long dx = column.x - origin.blockX(), dz = column.z - origin.blockZ();
            return dx * dx + dz * dz;
        }));
        return result;
    }

    static void inspectColumn(Terrain terrain, TeleportTarget target, Column column, boolean allowAir,
                              Budget budget, Selection selection) {
        // Shape implementations can consult neighboring blocks. Keep that extra margin in the owning chunk.
        if (!withinChunk(column.x, column.z, 2)) return;
        if (!target.terrain()) {
            int centerY = (int) Math.floor(target.point().y());
            for (int delta = -LOCAL_VERTICAL_RADIUS; delta <= LOCAL_VERTICAL_RADIUS && budget.remaining > 0; delta++) {
                int y = centerY + delta;
                if (y < terrain.minHeight() + 1 || y + 1.8 >= terrain.maxHeight()) continue;
                double floor = surface(terrain.cell(column.x, y - 1, column.z), y - 1);
                selection.offer(probe(terrain, target, at(target, column, floor), Rule.NEARBY, allowAir, budget));
            }
        } else if (terrain.ceilingHeight() < terrain.maxHeight()) {
            netherColumn(terrain, target, column, allowAir, budget, selection);
        } else {
            // World surface is the bottom boundary of the sky-connected space. Never inspect caves below it.
            double floor = exposedFloor(terrain, column.x, column.z);
            selection.offer(probe(terrain, target, at(target, column, floor), Rule.OUTDOOR, false, budget));
            if (allowAir) {
                double openBottom = floor;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    openBottom = Math.max(openBottom, exposedFloor(terrain, column.x + dx, column.z + dz));
                }
                // Prefer the requested/central height; never search underground just to satisfy the claim's Y bounds.
                double y = Math.max(openBottom, Math.max(minY(terrain, target), Math.min(maxY(terrain, target), target.point().y())));
                selection.offer(probe(terrain, target, at(target, column, y), Rule.OUTDOOR, true, budget));
            }
        }
    }

    private static void netherColumn(Terrain terrain, TeleportTarget target, Column column, boolean allowAir,
                                     Budget budget, Selection selection) {
        // Nether has no sky. Skip its bedrock roof and use the first substantial open chamber, not narrow tunnels.
        int top = Math.min(terrain.ceilingHeight() - 6, (int) Math.floor(maxY(terrain, target) + 1.8));
        int bottom = minY(terrain, target) - 1;
        int airTop = top + 1;
        for (int y = top; y >= bottom; y--) {
            Cell cell = terrain.cell(column.x, y, column.z);
            if (!cell.dangerous && cell.shapes.isEmpty()) continue;
            if (airTop - y - 1 >= NETHER_OPEN_HEIGHT) {
                offerChamber(terrain, target, column, surface(cell, y), airTop, allowAir, budget, selection);
                return; // Do not tunnel through this floor into a lower cavity.
            }
            airTop = y;
        }
        if (allowAir && airTop - bottom - 1 >= NETHER_OPEN_HEIGHT) {
            offerChamber(terrain, target, column, bottom + 1, airTop, true, budget, selection);
        }
    }

    private static void offerChamber(Terrain terrain, TeleportTarget target, Column column, double floor, int top,
                                     boolean allowAir, Budget budget, Selection selection) {
        selection.offer(probe(terrain, target, at(target, column, floor), Rule.NETHER, false, budget));
        if (allowAir) {
            double y = Math.max(floor, Math.max(minY(terrain, target),
                    Math.min(Math.min(maxY(terrain, target), top - NETHER_OPEN_HEIGHT), target.point().y())));
            selection.offer(probe(terrain, target, at(target, column, y), Rule.NETHER, true, budget));
        }
    }

    private static Point at(TeleportTarget target, Column column, double y) {
        return new Point(column.x + .5, y, column.z + .5, target.point().yaw(), target.point().pitch());
    }

    private static int minY(Terrain terrain, TeleportTarget target) {
        return Math.max(terrain.minHeight() + 1, target.inside() == null ? terrain.minHeight() + 1 : target.inside().y1());
    }

    private static double maxY(Terrain terrain, TeleportTarget target) {
        return Math.min(terrain.maxHeight() - 2, target.inside() == null ? terrain.maxHeight() - 2 : target.inside().y2() - 2);
    }

    private static double surface(Cell cell, int blockY) {
        return blockY + cell.shapes.stream().mapToDouble(BoundingBox::getMaxY).max().orElse(1);
    }

    private static double exposedFloor(Terrain terrain, int x, int z) {
        // Height maps include flowers and tall grass. They are part of the open space, not a supporting floor.
        for (int y = Math.min(terrain.maxHeight() - 1, terrain.highestBlockYAt(x, z)); y >= terrain.minHeight(); y--) {
            Cell cell = terrain.cell(x, y, z);
            if (cell.dangerous || !cell.shapes.isEmpty()) return surface(cell, y);
        }
        return terrain.minHeight();
    }

    static Landing probe(Terrain terrain, TeleportTarget target, Point point, Rule rule, boolean allowAir, Budget budget) {
        if (!budget.take()) return null;
        Fit fit = fit(terrain, target, point);
        if (fit == Fit.BLOCKED || fit == Fit.AIR && !allowAir) return null;
        if (!openForRule(terrain, point, rule)) return null;
        return new Landing(point, fit == Fit.AIR, rule);
    }

    boolean valid(World world, TeleportTarget target, Landing landing) {
        return valid(terrain(world, target), target, landing);
    }

    static boolean valid(Terrain terrain, TeleportTarget target, Landing landing) {
        Fit fit = fit(terrain, target, landing.point);
        // Ground disappearing after selection must not silently turn a supported landing into air.
        return fit != Fit.BLOCKED && (landing.airborne || fit == Fit.GROUNDED)
                && openForRule(terrain, landing.point, landing.rule);
    }

    private static boolean openForRule(Terrain terrain, Point point, Rule rule) {
        if (rule == Rule.EXPLICIT || rule == Rule.NEARBY) return true;
        int x = point.blockX(), z = point.blockZ();
        if (!withinChunk(x, z, 2)) return false;
        int height = rule == Rule.NETHER ? NETHER_OPEN_HEIGHT : OPEN_HEIGHT;
        if (point.y() + height > terrain.maxHeight()) return false;
        if (rule == Rule.NETHER && point.y() + height > terrain.ceilingHeight() - 5) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int cx = x + dx, cz = z + dz;
            if (rule == Rule.OUTDOOR && exposedFloor(terrain, cx, cz) > point.y() + 1.0e-6) return false;
            BoundingBox space = new BoundingBox(cx, point.y(), cz, cx + 1, point.y() + height, cz + 1);
            for (int y = (int) Math.floor(point.y()); y < Math.ceil(point.y() + height); y++) {
                Cell cell = terrain.cell(cx, y, cz);
                if (cell.dangerous) return false;
                for (BoundingBox shape : cell.shapes) if (space.overlaps(shape.clone().shift(cx, y, cz))) return false;
            }
        }
        return true;
    }

    private Terrain terrain(World world, TeleportTarget target) {
        return new Terrain() {
            public int minHeight() { return world.getMinHeight(); }
            public int maxHeight() { return world.getMaxHeight(); }
            public int ceilingHeight() { return world.hasCeiling() ? Math.min(world.getMaxHeight(), world.getLogicalHeight()) : world.getMaxHeight(); }
            public int highestBlockYAt(int x, int z) { return world.getHighestBlockYAt(x, z); }
            public Cell cell(int x, int y, int z) {
                Block block = world.getBlockAt(x, y, z);
                boolean dangerous = hazard(block.getType()) || block.isLiquid()
                        || block.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
                return new Cell(dangerous, dangerous ? List.of() : List.copyOf(block.getCollisionShape().getBoundingBoxes()));
            }
            public boolean permitsColumn(int x, double y, int z) {
                org.bukkit.Location location = new org.bukkit.Location(world, x + .5, y, z + .5);
                if (!world.getWorldBorder().isInside(location)) return false;
                DominionDTO actual = CacheManager.instance.getDominion(location);
                if (target.inside() != null && actual != null && actual.getId() != target.dominionId()) return false;
                return target.outside() == null || actual == null;
            }
        };
    }

    interface Terrain {
        int minHeight();
        int maxHeight();
        default int ceilingHeight() { return maxHeight(); }
        int highestBlockYAt(int x, int z);
        Cell cell(int x, int y, int z);
        boolean permitsColumn(int x, double y, int z);
    }

    record Cell(boolean dangerous, List<BoundingBox> shapes) { }

    static boolean safe(Terrain terrain, TeleportTarget target, Point point) { return fit(terrain, target, point) == Fit.GROUNDED; }

    private static boolean withinChunk(int x, int z, int margin) {
        return ((x - margin) >> 4) == (x >> 4) && ((x + margin) >> 4) == (x >> 4)
                && ((z - margin) >> 4) == (z >> 4) && ((z + margin) >> 4) == (z >> 4);
    }

    static Fit fit(Terrain terrain, TeleportTarget target, Point point) {
        if (!target.permits(point) || point.y() < terrain.minHeight() + 1 || point.y() + 1.8 >= terrain.maxHeight()) return Fit.BLOCKED;
        int minX = (int) Math.floor(point.x() - .3), maxX = (int) Math.floor(point.x() + .3);
        int minZ = (int) Math.floor(point.z() - .3), maxZ = (int) Math.floor(point.z() + .3);
        int chunkX = point.blockX() >> 4, chunkZ = point.blockZ() >> 4;
        if (((minX - 1) >> 4) != chunkX || ((maxX + 1) >> 4) != chunkX
                || ((minZ - 1) >> 4) != chunkZ || ((maxZ + 1) >> 4) != chunkZ) return Fit.BLOCKED;
        BoundingBox body = new BoundingBox(point.x() - .3, point.y(), point.z() - .3,
                point.x() + .3, point.y() + 1.8, point.z() + .3);
        boolean supported = true;
        int floorY = (int) Math.floor(point.y() - 1.0e-6);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!terrain.permitsColumn(x, point.y(), z)) return Fit.BLOCKED;
            double left = Math.max(body.getMinX(), x), right = Math.min(body.getMaxX(), x + 1);
            double front = Math.max(body.getMinZ(), z), back = Math.min(body.getMaxZ(), z + 1);
            boolean columnSupport = false;
            for (int y = floorY; y <= Math.floor(body.getMaxY()); y++) {
                Cell cell = terrain.cell(x, y, z);
                if (cell.dangerous) return Fit.BLOCKED;
                for (BoundingBox local : cell.shapes) {
                    BoundingBox shape = local.clone().shift(x, y, z);
                    if (body.overlaps(shape)) return Fit.BLOCKED;
                    if (Math.abs(shape.getMaxY() - point.y()) < 1.0e-6
                            && shape.getMinX() <= left && shape.getMaxX() >= right
                            && shape.getMinZ() <= front && shape.getMaxZ() >= back) columnSupport = true;
                }
            }
            supported &= columnSupport;
        }
        return supported ? Fit.GROUNDED : Fit.AIR;
    }

    static boolean hazard(Material material) { return HAZARDS.contains(material.name()); }
}
