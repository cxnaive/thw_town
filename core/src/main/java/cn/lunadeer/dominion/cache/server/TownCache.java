package cn.lunadeer.dominion.cache.server;

import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global town cache (town + town_member + town_trust). Towns are
 * cross-server entities with no server_id, so — like PlayerCache — this
 * cache hangs off CacheManager directly rather than ServerCache, and every
 * backend keeps a full read replica. Cross-server freshness follows the
 * existing model: local write-through invalidation with the 4s throttled
 * reload, DB as source of truth.
 */
public class TownCache extends Cache {

    private volatile ConcurrentHashMap<Integer, TownDOO> townsById = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, Integer> townNameToId = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<Integer, List<TownMemberDOO>> membersByTown = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<UUID, List<TownMemberDOO>> membersByPlayer = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<Integer, List<TownTrustDOO>> trustsByTown = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<UUID, List<TownTrustDOO>> trustsByPlayer = new ConcurrentHashMap<>();

    /**
     * Immediate synchronous reload, bypassing the base class throttle.
     * TownManager write paths use this so a follow-up command (invite
     * right after create, join right after attach) never reads a cache
     * that is up to 4s stale.
     */
    public void reloadNow() {
        try {
            loadExecution();
        } catch (Exception e) {
            cn.lunadeer.dominion.utils.XLogger.error(e);
        }
    }

    @Override
    synchronized void loadExecution() throws Exception {
        // read+swap under one lock: two concurrent loads must not let the
        // older DB snapshot overwrite the newer one
        ConcurrentHashMap<Integer, TownDOO> tempTownsById = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Integer> tempTownNameToId = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, List<TownMemberDOO>> tempMembersByTown = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, List<TownMemberDOO>> tempMembersByPlayer = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, List<TownTrustDOO>> tempTrustsByTown = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, List<TownTrustDOO>> tempTrustsByPlayer = new ConcurrentHashMap<>();

        for (TownDOO town : TownDOO.selectAll()) {
            if (town.getStatus() == TownDOO.TownStatus.DISBANDED) continue;
            tempTownsById.put(town.getId(), town);
            tempTownNameToId.put(town.getName().toLowerCase(), town.getId());
        }
        for (TownMemberDOO member : TownMemberDOO.selectAll()) {
            if (!tempTownsById.containsKey(member.getTownId())) continue;
            tempMembersByTown.computeIfAbsent(member.getTownId(), k -> new ArrayList<>()).add(member);
            tempMembersByPlayer.computeIfAbsent(member.getPlayerUuid(), k -> new ArrayList<>()).add(member);
        }
        for (TownTrustDOO trust : TownTrustDOO.selectAll()) {
            if (!tempTownsById.containsKey(trust.getTownId())) continue;
            tempTrustsByTown.computeIfAbsent(trust.getTownId(), k -> new ArrayList<>()).add(trust);
            tempTrustsByPlayer.computeIfAbsent(trust.getPlayerUuid(), k -> new ArrayList<>()).add(trust);
        }

        townsById = tempTownsById;
        townNameToId = tempTownNameToId;
        membersByTown = tempMembersByTown;
        membersByPlayer = tempMembersByPlayer;
        trustsByTown = tempTrustsByTown;
        trustsByPlayer = tempTrustsByPlayer;
    }

    /**
     * Towns are small in number and their member/trust rows fan out across
     * three tables; a single-row incremental load is not meaningfully
     * cheaper than a full reload, so this delegates to loadExecution().
     */
    @Override
    void loadExecution(Integer idToLoad) throws Exception {
        loadExecution();
    }

    @Override
    void deleteExecution(Integer idToDelete) throws Exception {
        loadExecution();
    }

    public @Nullable TownDOO getTown(@NotNull Integer id) {
        return townsById.get(id);
    }

    public @Nullable TownDOO getTown(@NotNull String name) {
        Integer id = townNameToId.get(name.toLowerCase());
        return id == null ? null : townsById.get(id);
    }

    public @NotNull List<TownDOO> getAllTowns() {
        return new ArrayList<>(townsById.values());
    }

    public @NotNull List<TownMemberDOO> getMembersOf(@NotNull Integer townId) {
        return membersByTown.getOrDefault(townId, List.of());
    }

    /**
     * All formal memberships of a player (one entry per town).
     */
    public @NotNull List<TownMemberDOO> getMembershipsOf(@NotNull UUID player) {
        return membersByPlayer.getOrDefault(player, List.of());
    }

    public @Nullable TownMemberDOO getMember(@NotNull Integer townId, @NotNull UUID player) {
        for (TownMemberDOO member : getMembersOf(townId)) {
            if (member.getPlayerUuid().equals(player)) return member;
        }
        return null;
    }

    public @NotNull List<TownTrustDOO> getTrustsOf(@NotNull Integer townId) {
        return trustsByTown.getOrDefault(townId, List.of());
    }

    public @NotNull List<TownTrustDOO> getTrustsOf(@NotNull UUID player) {
        return trustsByPlayer.getOrDefault(player, List.of());
    }

    /**
     * The trust grant effective for a player on a specific outpost: an
     * OUTPOST-scoped row matching the dominion wins over a TOWN-scoped row.
     */
    public @Nullable TownTrustDOO getEffectiveTrust(@NotNull Integer townId, @NotNull UUID player, @NotNull Integer dominionId) {
        TownTrustDOO townScope = null;
        for (TownTrustDOO trust : getTrustsOf(townId)) {
            if (!trust.getPlayerUuid().equals(player)) continue;
            if (trust.getScopeType() == TownTrustDOO.TrustScope.OUTPOST && trust.getScopeId().equals(dominionId)) {
                return trust;
            }
            if (trust.getScopeType() == TownTrustDOO.TrustScope.TOWN) {
                townScope = trust;
            }
        }
        return townScope;
    }
}
