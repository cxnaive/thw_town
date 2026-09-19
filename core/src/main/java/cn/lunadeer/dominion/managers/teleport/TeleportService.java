package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.managers.MultiServerManager;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.scheduler.CancellableTask;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

import static cn.lunadeer.dominion.managers.teleport.TeleportRequest.State.*;
import static cn.lunadeer.dominion.misc.Others.checkPrivilegeFlagSilence;

/** Player operations are entity-scheduled; DB and terrain work never run on that scheduler. */
public final class TeleportService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final TeleportPlatform platform;
    private final SafeLandingResolver landing;
    private final TeleportHandoffStore handoffs = new TeleportHandoffStore();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TeleportRequest> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final CancellableTask purgeTask;
    private volatile boolean closed;

    private static final class Session {
        final Player player;
        final UUID playerId;
        final UUID id = UUID.randomUUID();
        long revision;

        Session(Player player) { this.player = player; playerId = player.getUniqueId(); }
    }

    public TeleportService(JavaPlugin plugin) {
        this.plugin = plugin;
        platform = new TeleportPlatform(plugin);
        landing = new SafeLandingResolver(platform);
        purgeTask = Scheduler.runTaskRepeatAsync(() -> {
            if (closed) return;
            try { handoffs.purge(System.currentTimeMillis()); }
            catch (Exception exception) { plugin.getLogger().log(Level.WARNING, "Teleport handoff cleanup failed", exception); }
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= System.currentTimeMillis());
        }, 20, 1200);
    }

    public void teleport(Player player, int dominionId) {
        platform.player(player, () -> begin(session(player), dominionId, null, null), () -> { });
    }

    public void eject(Player player, Location location, DominionDTO excluded) {
        if (location.getWorld() == null) return;
        TeleportTarget target = new TeleportTarget(Configuration.multiServer.serverId, -1, location.getWorld().getUID(),
                TeleportTarget.Point.of(location), false, null,
                excluded == null ? null : TeleportTarget.Bounds.of(excluded.getCuboid()));
        platform.player(player, () -> begin(session(player), -1, target, null), () -> { });
    }

    private Session session(Player player) {
        return sessions.compute(player.getUniqueId(), (id, old) -> old != null && old.player == player ? old : new Session(player));
    }

    private void begin(Session session, int dominionId, TeleportTarget internal, UUID handoffId) {
        if (closed || !session.player.isOnline()) return;
        TeleportRequest old = requests.get(session.playerId);
        if (old != null) {
            if (internal != null && old.internal) return;
            if (old.state() == EXECUTING || old.state() == HANDOFF || old.internal
                    || internal == null && old.dominionId == dominionId) {
                Notification.warn(session.player, Language.teleportManagerText.busy);
                return;
            }
            end(session, old, CANCELLED, Language.teleportManagerText.unfinishedCancelled, false);
        }
        session.revision++;
        Location origin = session.player.getLocation();
        TeleportRequest request = new TeleportRequest(handoffId == null ? UUID.randomUUID() : handoffId,
                session.id, dominionId, internal != null, new TeleportRequest.Origin(origin.getWorld().getUID(),
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()));
        requests.put(session.playerId, request);
        int delay = internal != null || handoffId != null || bypass(session.player) ? 0
                : Math.max(0, Configuration.getPlayerLimitation(session.player).teleportation.delay);
        later(session, request, delay + timeoutSeconds(), () -> {
            if (request.state() == EXECUTING) {
                Notification.warn(session.player, Language.teleportManagerText.unconfirmed);
                log(session, request, "execution-unconfirmed", null, null);
            } else {
                end(session, request, FAILED, Language.teleportManagerText.timedOut, false);
            }
        });
        if (ChestUserInterfaceManager.hasTag(session.player.getOpenInventory().getItem(0))) session.player.closeInventory();
        if (internal != null) {
            request.transition(WAITING, PREPARING);
            prepare(session, request, internal);
            return;
        }
        readDominion(session, request, dominion -> {
            if (!authorize(session, request, dominion, true)) return;
            if (handoffId != null && dominion.getServerId() != Configuration.multiServer.serverId) {
                end(session, request, FAILED, Language.teleportManagerText.targetChanged, false);
                return;
            }
            if (delay > 0) Notification.info(session.player, Language.teleportManagerText.delay, delay);
            else Notification.info(session.player, Language.teleportManagerText.preparing);
            later(session, request, delay, () -> {
                if (!request.transition(WAITING, PREPARING)) return;
                readDominion(session, request, current -> {
                    if (!authorize(session, request, current, false)) return;
                    if (current.getServerId() == Configuration.multiServer.serverId) prepare(session, request, TeleportTarget.of(current));
                    else if (handoffId == null) handoff(session, request, current);
                    else end(session, request, FAILED, Language.teleportManagerText.targetChanged, false);
                });
            });
        });
    }

    private void readDominion(Session session, TeleportRequest request, Consumer<DominionDOO> action) {
        complete(session, request, platform.async(() -> DominionDOO.select(request.dominionId)), dominion -> {
            if (dominion == null) end(session, request, FAILED, Language.teleportManagerText.targetMissing, false);
            else action.accept(dominion);
        }, "read-dominion");
    }

    private boolean authorize(Session session, TeleportRequest request, DominionDOO dominion, boolean cooldown) {
        Player player = session.player;
        if ((!Configuration.getPlayerLimitation(player).teleportation.enable && !bypass(player)) || !Flags.TELEPORT.getEnable()) {
            end(session, request, FAILED, Language.teleportManagerText.disabled, false);
            return false;
        }
        if (!checkPrivilegeFlagSilence(dominion, Flags.TELEPORT, player, null)) {
            end(session, request, FAILED, Language.teleportManagerText.permissionDenied, false);
            return false;
        }
        if (dominion.getServerId() != Configuration.multiServer.serverId && !Configuration.multiServer.enable) {
            end(session, request, FAILED, Language.teleportManagerText.multiServerDisabled, false);
            return false;
        }
        long remaining = cooldowns.getOrDefault(session.playerId, 0L) - System.currentTimeMillis();
        if (cooldown && !bypass(player) && Configuration.getPlayerLimitation(player).teleportation.cooldown > 0 && remaining > 0) {
            if (end(session, request, FAILED, null, false))
                Notification.warn(player, Language.teleportManagerText.coolingDown, (remaining + 999) / 1000);
            return false;
        }
        return true;
    }

    private void prepare(Session session, TeleportRequest request, TeleportTarget target) {
        request.target = target;
        World world = Bukkit.getWorld(target.worldId());
        if (world == null) {
            end(session, request, FAILED, Language.teleportManagerText.worldUnavailable, false);
            return;
        }
        complete(session, request, landing.resolve(world, target, request, Configuration.safeTeleport.searchRadius,
                Configuration.safeTeleport.maxCandidates), destination -> {
            if (destination == null) {
                end(session, request, FAILED, Language.teleportManagerText.noSafeLocation, false);
                return;
            }
            if (request.internal) recheckLanding(session, request, target, world, destination, null);
            else readDominion(session, request, current -> {
                if (!authorize(session, request, current, false)) return;
                if (!target.equals(TeleportTarget.of(current))) {
                    end(session, request, FAILED, Language.teleportManagerText.targetChanged, false);
                    return;
                }
                recheckLanding(session, request, target, world, destination, current);
            });
        }, "prepare " + target);
    }

    private void recheckLanding(Session session, TeleportRequest request, TeleportTarget target, World world,
                                SafeLandingResolver.Landing destination, DominionDOO dominion) {
        TeleportTarget.Point point = destination.point();
        complete(session, request, platform.region(world, point.blockX() >> 4, point.blockZ() >> 4,
                () -> request.preparing() && landing.valid(world, target, destination)), safe -> {
            if (!safe) {
                end(session, request, FAILED, Language.teleportManagerText.noSafeLocation, false);
                return;
            }
            if (!request.internal && !authorize(session, request, dominion, false)) return;
            if (request.internal && target.outside() != null) {
                Location current = session.player.getLocation();
                if (!current.getWorld().getUID().equals(target.worldId())
                        || !target.outside().contains(current.getX(), current.getY(), current.getZ())) {
                    end(session, request, CANCELLED, null, false);
                    return;
                }
            }
            if (session.player.isDead() || !request.transition(PREPARING, EXECUTING)) {
                end(session, request, FAILED, Language.teleportManagerText.rejected, false);
                return;
            }
            // Do not cancel/supersede after this point: teleportAsync has no reliable rollback.
            complete(session, request, platform.teleport(session.player, world, point), success -> {
                if (Boolean.TRUE.equals(success)) {
                    if (!request.internal && !bypass(session.player)) {
                        int cooldown = Configuration.getPlayerLimitation(session.player).teleportation.cooldown;
                        cooldowns.put(session.playerId, System.currentTimeMillis() + Math.max(0, cooldown) * 1000L);
                    }
                    end(session, request, SUCCEEDED, request.internal ? null : destination.airborne()
                            ? Language.teleportManagerText.airborneSuccess : Language.teleportManagerText.success, false);
                } else end(session, request, FAILED, Language.teleportManagerText.rejected, false);
            }, "execute " + target);
        }, "recheck " + target);
    }

    private void handoff(Session session, TeleportRequest request, DominionDOO dominion) {
        if (!request.transition(PREPARING, HANDOFF)) return;
        int serverId = dominion.getServerId();
        complete(session, request, platform.async(() -> {
            if (!current(session, request)) return null;
            String name = MultiServerManager.instance.getServerName(serverId);
            if (name == null || name.isBlank()) throw new IllegalStateException("Unknown target server " + serverId);
            handoffs.purge(System.currentTimeMillis());
            handoffs.save(new TeleportHandoffStore.Handoff(session.playerId, request.id, request.dominionId,
                    serverId, System.currentTimeMillis() + 60000));
            if (!current(session, request)) {
                handoffs.remove(session.playerId, request.id);
                return null;
            }
            return name;
        }), name -> {
            if (name == null) return;
            Notification.info(session.player, Language.teleportManagerText.connecting);
            MultiServerManager.instance.connectToServer(session.player, name);
        }, "handoff server=" + serverId);
    }

    public void joined(Player player) {
        Session session = new Session(player);
        Session old = sessions.put(session.playerId, session);
        if (old != null) retire(old);
        if (!Configuration.multiServer.enable) return;
        long revision = session.revision;
        platform.async(() -> handoffs.claim(session.playerId, Configuration.multiServer.serverId, System.currentTimeMillis()))
                .whenComplete((handoff, error) -> platform.player(player, () -> {
                    if (closed || sessions.get(session.playerId) != session || session.revision != revision) return;
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not claim teleport handoff for " + session.playerId, error);
                        Notification.error(player, Language.teleportManagerText.failed);
                    } else if (handoff != null) begin(session, handoff.dominionId(), null, handoff.requestId());
                }, () -> retire(session)));
    }

    public void moved(Player player, Location from, Location to, boolean teleport) {
        if (to == null || (!teleport && sameBlock(from, to))) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        TeleportRequest request = requests.get(session.playerId);
        if (request != null && (!request.internal || !from.getWorld().equals(to.getWorld()))) {
            end(session, request, CANCELLED, Language.teleportManagerText.cancelMove, false);
        }
    }

    public static boolean sameBlock(Location from, Location to) {
        return from.getWorld().equals(to.getWorld()) && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ();
    }

    public void quit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player) {
            sessions.remove(session.playerId, session);
            retire(session);
        }
    }

    private void retire(Session session) {
        TeleportRequest request = requests.get(session.playerId);
        if (request != null && request.session.equals(session.id)) end(session, request, CANCELLED, null, true);
    }

    private boolean current(Session session, TeleportRequest request) {
        return !closed && sessions.get(session.playerId) == session && requests.get(session.playerId) == request && !request.terminal();
    }

    private void later(Session session, TeleportRequest request, int seconds, Runnable action) {
        CancellableTask task = Scheduler.runTaskLaterAsync(() -> onPlayer(session, request, action), seconds * 20L);
        if (task != null) request.cleanup(task::cancel);
    }

    private <T> void complete(Session session, TeleportRequest request, CompletableFuture<T> future,
                              Consumer<T> action, String stage) {
        future.whenComplete((value, error) -> onPlayer(session, request, () -> {
            if (error != null) failure(session, request, stage, error);
            else action.accept(value);
        }));
    }

    private void onPlayer(Session session, TeleportRequest request, Runnable action) {
        if (!current(session, request)) return;
        platform.player(session.player, () -> {
            if (!current(session, request)) return;
            try {
                // Some server-native teleports bypass Bukkit move/teleport events. Validate the
                // original position at every pre-submission continuation as a second line of defence.
                if (request.state() != EXECUTING && !request.internal) {
                    Location location = session.player.getLocation();
                    if (!request.origin.matches(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                        end(session, request, CANCELLED, Language.teleportManagerText.cancelMove, false);
                        return;
                    }
                }
                action.run();
            }
            catch (Exception exception) { failure(session, request, "callback", exception); }
        }, () -> retire(session));
    }

    private void failure(Session session, TeleportRequest request, String stage, Throwable error) {
        log(session, request, stage, null, error);
        end(session, request, FAILED, Language.teleportManagerText.failed, false);
    }

    private boolean end(Session session, TeleportRequest request, TeleportRequest.State state, String message, boolean retired) {
        boolean hadHandoff = request.state() == HANDOFF;
        boolean preserveHandoff = retired && request.state() == HANDOFF;
        if (!request.finish(state, retired)) return false;
        requests.remove(session.playerId, request);
        if (hadHandoff && !preserveHandoff && !closed) {
            platform.async(() -> { handoffs.remove(session.playerId, request.id); return null; })
                    .exceptionally(error -> { log(session, request, "handoff-cleanup", null, error); return null; });
        }
        if (message != null && !retired && !closed && sessions.get(session.playerId) == session) {
            if (state == SUCCEEDED) Notification.info(session.player, message);
            else Notification.warn(session.player, message);
        }
        return true;
    }

    private void log(Session session, TeleportRequest request, String stage, TeleportTarget target, Throwable error) {
        plugin.getLogger().log(Level.WARNING, "Teleport request=" + request.id + " session=" + session.id
                + " player=" + session.playerId + " dominion=" + request.dominionId + " state=" + request.state()
                + " origin=" + request.origin + " stage=" + stage
                + " target=" + (target == null ? request.target : target), error);
    }

    private boolean bypass(Player player) { return Configuration.adminBypass && player.hasPermission(Dominion.adminPermission); }
    private int timeoutSeconds() { return Math.max(2, Math.min(120, Configuration.safeTeleport.prepareTimeoutSeconds)); }

    @Override
    public void close() {
        closed = true;
        purgeTask.cancel();
        sessions.values().forEach(this::retire);
        sessions.clear();
        cooldowns.clear();
        platform.close();
    }
}
