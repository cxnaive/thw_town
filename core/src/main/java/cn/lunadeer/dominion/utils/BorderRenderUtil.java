package cn.lunadeer.dominion.utils;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Utility class for displaying dominion borders using particles.
 * Renders the border as FLAME particles along the edges of the cuboid.
 */
public class BorderRenderUtil {

    /** Default glow color for API compatibility (not used in particle mode) */
    public static final Color DEFAULT_GLOW_COLOR = Color.fromRGB(0, 180, 255);

    /** Maximum render radius for particles */
    private static final int renderMaxRadius = 48;

    public static void showBorder(CommandSender sender, DominionDTO dominion) {
        if (!(sender instanceof Player player)) {
            return;
        }
        showBorder(player,
                dominion.getWorld(),
                dominion.getCuboid()
        );
    }

    public static void showBorder(Player player, DominionDTO dominion) {
        showBorder(player,
                dominion.getWorld(),
                dominion.getCuboid()
        );
    }

    public static void showBorder(Player player, World world, CuboidDTO cuboid, Color color) {
        // Color parameter is ignored in particle mode - FLAME particles are used
        showBorder(player, world, cuboid);
    }

    public static void showBorder(Player player, World world, CuboidDTO cuboid) {
        showBoxFace(player,
                world,
                cuboid.x1(),
                cuboid.y1(),
                cuboid.z1(),
                cuboid.x2(),
                cuboid.y2(),
                cuboid.z2()
        );
    }

    public static void showBoxFace(Player player, World world,
                                   int x1, int y1, int z1, int x2, int y2, int z2) {
        Location loc1 = new Location(world, x1, y1, z1);
        Location loc2 = new Location(world, x2, y2, z2);
        showBoxFace(player, loc1, loc2);
    }

    public static void showBoxFace(Player player, Location loc1, Location loc2) {
        Scheduler.runTask(() -> {
            if (!loc1.getWorld().equals(loc2.getWorld())) {
                return;
            }
            int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
            int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
            int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
            int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
            int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
            int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

            int player_minx = player.getLocation().getBlockX() - renderMaxRadius;
            int player_maxx = player.getLocation().getBlockX() + renderMaxRadius;
            int player_miny = player.getLocation().getBlockY() - renderMaxRadius / 2;
            int player_maxy = player.getLocation().getBlockY() + renderMaxRadius / 2;
            int player_minz = player.getLocation().getBlockZ() - renderMaxRadius;
            int player_maxz = player.getLocation().getBlockZ() + renderMaxRadius;

            boolean skip_minx = false;
            boolean skip_maxx = false;
            boolean skip_minz = false;
            boolean skip_maxz = false;

            int[] adjustedX = adjustBoundary(player_minx, player_maxx, minX, maxX);
            if (minX != adjustedX[0]) {
                skip_minx = true;
                minX = adjustedX[0];
            }
            if (maxX != adjustedX[1]) {
                skip_maxx = true;
                maxX = adjustedX[1];
            }

            int[] adjustedZ = adjustBoundary(player_minz, player_maxz, minZ, maxZ);
            if (minZ != adjustedZ[0]) {
                skip_minz = true;
                minZ = adjustedZ[0];
            }
            if (maxZ != adjustedZ[1]) {
                skip_maxz = true;
                maxZ = adjustedZ[1];
            }

            int[] adjustedY = adjustBoundary(player_miny, player_maxy, minY, maxY);
            minY = adjustedY[0];
            maxY = adjustedY[1];

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!skip_minz) {
                        spawnParticle(player, x, y, minZ);
                    }
                    if (!skip_maxz) {
                        spawnParticle(player, x, y, maxZ);
                    }
                }
                for (int z = minZ; z <= maxZ; z++) {
                    if (!skip_minx) {
                        spawnParticle(player, minX, y, z);
                    }
                    if (!skip_maxx) {
                        spawnParticle(player, maxX, y, z);
                    }
                }
            }
        });
    }

    private static void spawnParticle(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.FLAME, x, y, z, 2, 0, 0, 0, 0);
    }

    private static int[] adjustBoundary(int playerMin, int playerMax, int boundaryMin, int boundaryMax) {
        if (playerMax <= boundaryMin) {
            boundaryMin = boundaryMax;
        } else if (playerMax <= boundaryMax) {
            boundaryMax = playerMax;
            if (playerMin >= boundaryMin) {
                boundaryMin = playerMin;
            }
        } else {
            if (playerMin > boundaryMin) {
                boundaryMin = playerMin;
            } else if (playerMin > boundaryMax) {
                boundaryMin = boundaryMax;
            }
        }
        return new int[]{boundaryMin, boundaryMax};
    }

}
