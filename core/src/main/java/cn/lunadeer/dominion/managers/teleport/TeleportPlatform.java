package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static cn.lunadeer.dominion.utils.Misc.isPaper;

/** The only layer allowed to load chunks or invoke the server teleport implementation. */
public class TeleportPlatform {
    private final JavaPlugin plugin;
    private final boolean paper;
    private final Map<ChunkKey, Integer> tickets = new HashMap<>();
    private volatile boolean closed;

    private record ChunkKey(UUID world, int x, int z) { }

    public TeleportPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        paper = isPaper();
    }

    public void player(Player player, Runnable action, Runnable retired) {
        if (closed) { retired.run(); return; }
        if (paper) {
            var task = player.getScheduler().run(plugin, ignored -> action.run(), retired);
            if (task == null) retired.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) action.run();
                else retired.run();
            });
        }
    }

    public <T> CompletableFuture<T> async(CheckedSupplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Teleport service stopped"));
        Scheduler.runTaskAsync(() -> {
            try {
                if (closed) throw new IllegalStateException("Teleport service stopped");
                result.complete(action.get());
            } catch (Exception exception) { result.completeExceptionally(exception); }
        });
        return result;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> { T get() throws Exception; }

    public <T> CompletableFuture<T> region(World world, int x, int z, CheckedSupplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Teleport service stopped"));
        Runnable task = () -> {
            try {
                if (closed || Bukkit.getWorld(world.getUID()) != world) throw new IllegalStateException("Target world unavailable");
                result.complete(action.get());
            } catch (Exception exception) { result.completeExceptionally(exception); }
        };
        try {
            if (paper) Bukkit.getRegionScheduler().run(plugin, world, x, z, ignored -> task.run());
            else Bukkit.getScheduler().runTask(plugin, task);
        } catch (Exception exception) { result.completeExceptionally(exception); }
        return result;
    }

    public CompletableFuture<ChunkLease> prepare(World world, int x, int z, BooleanSupplier active) {
        // Even the *invocation* of getChunkAtAsync must originate in the target region on Lophine.
        return region(world, x, z, () -> {
            if (!active.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (paper) return world.getChunkAtAsync(x, z, true);
            return CompletableFuture.completedFuture(world.getChunkAt(x, z));
        }).thenCompose(future -> future).thenCompose(chunk -> region(world, x, z, () -> {
            if (!active.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            ChunkKey key = new ChunkKey(world.getUID(), x, z);
            synchronized (tickets) {
                if (closed || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if (!tickets.containsKey(key)) world.addPluginChunkTicket(x, z, plugin);
                tickets.merge(key, 1, Integer::sum);
            }
            return new ChunkLease(world, key);
        }));
    }

    public final class ChunkLease implements AutoCloseable {
        private final World world;
        private final ChunkKey key;
        private final AtomicBoolean released = new AtomicBoolean();

        private ChunkLease(World world, ChunkKey key) { this.world = world; this.key = key; }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true) || closed) return;
            region(world, key.x, key.z, () -> {
                synchronized (tickets) {
                    Integer count = tickets.get(key);
                    if (count == null) return null;
                    if (count == 1) {
                        world.removePluginChunkTicket(key.x, key.z, plugin);
                        tickets.remove(key);
                    } else tickets.put(key, count - 1);
                }
                return null;
            }).exceptionally(error -> {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not release teleport chunk " + key, error);
                return null;
            });
        }
    }

    /** Must be called on the player's scheduler; destination has already been prepared. */
    public CompletableFuture<Boolean> teleport(Player player, World world, TeleportTarget.Point point) {
        player.getPassengers().forEach(player::removePassenger);
        // leaveVehicle updates both sides on the entity's owning region; do not capture a later getVehicle().
        if (player.isInsideVehicle() && !player.leaveVehicle()) return CompletableFuture.completedFuture(false);
        if (paper) return player.teleportAsync(point.location(world), PlayerTeleportEvent.TeleportCause.PLUGIN);
        return CompletableFuture.completedFuture(player.teleport(point.location(world), PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    public void close() {
        synchronized (tickets) {
            closed = true;
            // Plugin-wide ticket removal does not read terrain. Serialize it with late acquisitions.
            for (World world : Bukkit.getWorlds()) world.removePluginChunkTickets(plugin);
            tickets.clear();
        }
    }
}
