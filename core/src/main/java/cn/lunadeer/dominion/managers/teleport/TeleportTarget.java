package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.doos.DominionDOO;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/** Immutable coordinates only; constructing a target never loads terrain. */
public record TeleportTarget(int serverId, int dominionId, UUID worldId, Point point,
                             boolean terrain, Bounds inside, Bounds outside) {
    public record Point(double x, double y, double z, float yaw, float pitch) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Non-finite teleport position");
            }
        }

        public static Point of(Location location) {
            return new Point(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }

        public Location location(World world) { return new Location(world, x, y, z, yaw, pitch); }
        public int blockX() { return (int) Math.floor(x); }
        public int blockZ() { return (int) Math.floor(z); }
    }

    public record Bounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        public static Bounds of(CuboidDTO cuboid) {
            return new Bounds(cuboid.x1(), cuboid.y1(), cuboid.z1(), cuboid.x2(), cuboid.y2(), cuboid.z2());
        }

        public boolean contains(double x, double y, double z) {
            return x >= x1 && x < x2 && y >= y1 && y < y2 && z >= z1 && z < z2;
        }
    }

    public static TeleportTarget of(DominionDOO dominion) {
        Bounds bounds = Bounds.of(dominion.getCuboid());
        String stored = dominion.getStoredTpLocation();
        Point center = new Point(((double) bounds.x1 + bounds.x2) / 2,
                ((double) bounds.y1 + bounds.y2) / 2, ((double) bounds.z1 + bounds.z2) / 2, 0, 0);
        return new TeleportTarget(dominion.getServerId(), dominion.getId(), dominion.getWorldUid(),
                "default".equals(stored) ? center : parse(stored), "default".equals(stored), bounds, null);
    }

    static Point parse(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3 && parts.length != 5) throw new IllegalArgumentException("Invalid teleport position: " + value);
        return new Point(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                parts.length == 5 ? Float.parseFloat(parts[3]) : 0, parts.length == 5 ? Float.parseFloat(parts[4]) : 0);
    }

    public boolean permits(Point point) {
        // Keep the complete standing player inside the claim, not just the feet.
        for (double x : new double[]{point.x - .3, point.x + .3}) {
            for (double z : new double[]{point.z - .3, point.z + .3}) {
                if (inside != null && (!inside.contains(x, point.y, z) || !inside.contains(x, point.y + 1.8, z))) return false;
                if (outside != null && point.y < outside.y2 && point.y + 1.8 > outside.y1
                        && x >= outside.x1 && x < outside.x2 && z >= outside.z1 && z < outside.z2) return false;
            }
        }
        return true;
    }
}
