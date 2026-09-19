package cn.lunadeer.dominion.cache.server;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.DominionNode;
import cn.lunadeer.dominion.cache.DominionNodeSectored;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.misc.DominionException;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DominionCache extends Cache {
    private final Integer serverId;

    // One publication point for data, ownership, hierarchy and spatial index.
    private record Snapshot(Map<Integer, DominionDTO> dominions,
                            Map<Integer, CopyOnWriteArrayList<Integer>> children,
                            Map<String, Integer> names,
                            Map<UUID, CopyOnWriteArrayList<Integer>> owners,
                            Map<UUID, CopyOnWriteArrayList<DominionNode>> playerNodes,
                            Map<Integer, DominionNode> rootNodes,
                            DominionNodeSectored sectors) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new DominionNodeSectored());
        }
    }

    private volatile Snapshot snapshot = Snapshot.empty();

    public DominionCache(Integer serverId) {
        this.serverId = serverId;
    }

    /**
     * Retrieves a DominionDTO by its ID.
     *
     * @param id the ID of the dominion to retrieve
     * @return the DominionDTO associated with the given ID
     * @throws DominionException if the dominion ID is not found
     */
    public @Nullable DominionDTO getDominion(@NotNull Integer id) {
        Map<Integer, DominionDTO> currentIdDominions = snapshot.dominions();
        return currentIdDominions != null ? currentIdDominions.get(id) : null;
    }

    /**
     * Retrieves a DominionDTO by its name.
     *
     * @param name the name of the dominion to retrieve
     * @return the DominionDTO associated with the given name
     * @throws DominionException if the dominion name is not found
     */
    public @Nullable DominionDTO getDominion(String name) {
        Snapshot current = snapshot;
        Map<String, Integer> currentDominionNameToId = current.names();
        if (currentDominionNameToId == null) return null;

        Integer id = currentDominionNameToId.get(name);
        try {
            if (id == null) return DominionDOO.select(name);
        } catch (Exception e) {
            return null;
        }
        return current.dominions().get(id);
    }

    /**
     * Retrieves a DominionDTO by its location.
     *
     * @param location the location of the dominion to retrieve
     * @return the DominionDTO associated with the given location, or null if not found
     */
    public @Nullable DominionDTO getDominion(@NotNull Location location) {
        return snapshot.sectors().getDominionByLocation(location);
    }

    @Nullable DominionDTO getDominion(UUID world, int x, int y, int z) {
        return snapshot.sectors().getDominionByPosition(world, x, y, z);
    }

    /**
     * Retrieves the dominion nodes managed by a player.
     *
     * @param player the UUID of the player
     * @return a list of DominionNode objects managed by the player
     */
    public @NotNull CopyOnWriteArrayList<DominionNode> getPlayerDominionNodes(UUID player) {
        Map<UUID, CopyOnWriteArrayList<DominionNode>> currentPlayerDominionNodes = snapshot.playerNodes();
        return currentPlayerDominionNodes != null ? currentPlayerDominionNodes.getOrDefault(player, new CopyOnWriteArrayList<>()) : new CopyOnWriteArrayList<>();
    }

    /**
     * Retrieves all dominion nodes.
     *
     * @return a list of all DominionNode objects
     */
    public @NotNull List<DominionNode> getAllDominionNodes() {
        Map<Integer, DominionNode> currentDominionNodeMap = snapshot.rootNodes();
        return currentDominionNodeMap != null ? new ArrayList<>(currentDominionNodeMap.values()) : new ArrayList<>();
    }

    /**
     * Retrieves the direct children of a dominion by its ID.
     *
     * @param id the ID of the parent dominion
     * @return a list of DominionDTO objects representing the children of the given dominion
     */
    public @NotNull List<DominionDTO> getChildrenOf(Integer id) {
        Snapshot current = snapshot;
        Map<Integer, CopyOnWriteArrayList<Integer>> currentDominionChildrenMap = current.children();
        if (currentDominionChildrenMap != null && currentDominionChildrenMap.containsKey(id)) {
            return currentDominionChildrenMap.get(id).stream().map(current.dominions()::get).filter(Objects::nonNull).toList();
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Retrieves the names of all dominions.
     *
     * @return a list of all dominion names
     */
    public List<String> getAllDominionNames() {
        Map<String, Integer> currentDominionNameToId = snapshot.names();
        return currentDominionNameToId != null ? new ArrayList<>(currentDominionNameToId.keySet()) : new ArrayList<>();
    }

    /**
     * Retrieves the DominionDTOs of the dominions owned by a player.
     *
     * @param player the UUID of the player
     * @return a list of DominionDTOs owned by the player
     */
    public CopyOnWriteArrayList<DominionDTO> getPlayerOwnDominionDTOs(UUID player) {
        Snapshot current = snapshot;
        Map<UUID, CopyOnWriteArrayList<Integer>> currentPlayerOwnDominions = current.owners();
        if (currentPlayerOwnDominions == null) return new CopyOnWriteArrayList<>();

        CopyOnWriteArrayList<Integer> dominionIds = currentPlayerOwnDominions.getOrDefault(player, new CopyOnWriteArrayList<>());
        CopyOnWriteArrayList<DominionDTO> dominions = new CopyOnWriteArrayList<>();
        for (Integer id : dominionIds) {
            DominionDTO dominion = current.dominions().get(id);
            if (dominion != null) {
                dominions.add(dominion);
            }
        }
        return dominions;
    }

    /**
     * Retrieves the top-level DominionDTOs owned by a player.
     * <p>
     * Top-level dominions are defined as those without a parent dominion (i.e., parentDomId == -1).
     *
     * @param player the UUID of the player
     * @return a list of top-level DominionDTOs owned by the player
     */
    public CopyOnWriteArrayList<DominionDTO> getPlayerOwnTopLevelDominionDTOs(UUID player) {
        CopyOnWriteArrayList<DominionDTO> allDominions = getPlayerOwnDominionDTOs(player);
        CopyOnWriteArrayList<DominionDTO> topLevelDominions = new CopyOnWriteArrayList<>();
        for (DominionDTO dominion : allDominions) {
            if (dominion.getParentDomId() == -1) {
                topLevelDominions.add(dominion);
            }
        }
        return topLevelDominions;
    }

    /**
     * Retrieves all dominions where the specified player has administrative rights.
     * <p>
     * This method checks all dominions where the player is a member and determines if
     * they have admin rights either directly or through a group they belong to.
     *
     * @param player the UUID of the player to check for admin rights
     * @return a list of DominionDTO objects where the player has administrative privileges
     */
    public List<DominionDTO> getPlayerAdminDominionDTOs(UUID player) {
        // Pre-fetch cache manager to avoid repeated lookups
        CacheManager cacheManager = CacheManager.instance;
        List<MemberDTO> playerBelongedDominionMembers = cacheManager.getCache().getMemberCache().getMemberBelongedDominions(player);

        List<DominionDTO> dominions = new ArrayList<>(playerBelongedDominionMembers.size()); // Pre-size for efficiency

        for (MemberDTO member : playerBelongedDominionMembers) {
            boolean hasAdminRights;

            if (member.getGroupId() == -1) {
                hasAdminRights = member.getFlagValue(Flags.ADMIN);
            } else {
                GroupDTO group = cacheManager.getGroup(member.getGroupId());
                hasAdminRights = group != null && group.getFlagValue(Flags.ADMIN);
            }

            if (hasAdminRights) {
                DominionDTO dominion = getDominion(member.getDomID());
                if (dominion != null) {
                    dominions.add(dominion);
                }
            }
        }

        return dominions;
    }

    public @NotNull List<DominionDTO> getAllDominions() {
        Map<Integer, DominionDTO> currentIdDominions = snapshot.dominions();
        return currentIdDominions != null ? new ArrayList<>(currentIdDominions.values()) : new ArrayList<>();
    }

    @Override
    synchronized void loadExecution() throws Exception {
        // Serialize the DB read too: a slow older reload cannot overwrite a newer one.
        replaceAll(new ArrayList<>(DominionDOO.selectAll(serverId)));
    }

    /** Build entirely off to the side; existing readers keep a valid old tree until publication. */
    synchronized void replaceAll(List<? extends DominionDTO> dominions) {
        Map<Integer, DominionDTO> byId = new HashMap<>();
        ConcurrentHashMap<Integer, CopyOnWriteArrayList<Integer>> children = new ConcurrentHashMap<>();
        Map<String, Integer> names = new HashMap<>();
        Map<UUID, CopyOnWriteArrayList<Integer>> owners = new HashMap<>();
        ConcurrentHashMap<Integer, DominionNode> rootNodes = new ConcurrentHashMap<>();
        ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> playerNodes = new ConcurrentHashMap<>();
        for (DominionDTO dominion : dominions) {
            byId.put(dominion.getId(), dominion);
            names.put(dominion.getName(), dominion.getId());
            owners.computeIfAbsent(dominion.getOwner(), key -> new CopyOnWriteArrayList<>()).add(dominion.getId());
            children.computeIfAbsent(dominion.getParentDomId(), key -> new CopyOnWriteArrayList<>()).add(dominion.getId());
        }
        CopyOnWriteArrayList<DominionNode> tree = DominionNode.BuildNodeTree(-1, new CopyOnWriteArrayList<>(dominions));
        parseNodeTree(playerNodes, rootNodes, tree, byId);
        DominionNodeSectored sectors = new DominionNodeSectored();
        sectors.build(tree);
        snapshot = new Snapshot(Map.copyOf(byId), Map.copyOf(children), Map.copyOf(names), Map.copyOf(owners),
                Map.copyOf(playerNodes), Map.copyOf(rootNodes), sectors);
    }

    @Override
    synchronized void loadExecution(Integer idToLoad) throws Exception {
        DominionDTO dominion = DominionDOO.select(idToLoad);
        if (dominion == null) {
            // Deletion may also reparent children, so fetch their current rows.
            loadExecution();
            return;
        }
        Map<Integer, DominionDTO> updated = new HashMap<>(snapshot.dominions());
        updated.remove(idToLoad);
        if (serverId.equals(dominion.getServerId())) updated.put(idToLoad, dominion);
        replaceAll(new ArrayList<>(updated.values()));
    }

    @Override
    synchronized void deleteExecution(Integer idToDelete) throws Exception {
        loadExecution(); // Includes cascades and preserved/reparented branches.
    }

    public void dominionNameUpdate(String oldName, String newName, Integer id) {
        load(id); // A rename uses the same publication path as every other update.
    }


    private static void parseNodeTree(ConcurrentHashMap<UUID, CopyOnWriteArrayList<DominionNode>> tempPlayerDominionNodes, ConcurrentHashMap<Integer, DominionNode> tempDominionNodeMap, CopyOnWriteArrayList<DominionNode> nodeTree, Map<Integer, DominionDTO> idDominions) {
        nodeTree.forEach(node -> {
            Integer dominionId = node.getDominionId();
            DominionDTO dominionDTO = idDominions.get(dominionId);
            if (dominionDTO != null) {
                tempDominionNodeMap.put(dominionId, node);
                tempPlayerDominionNodes.computeIfAbsent(dominionDTO.getOwner(), k -> new CopyOnWriteArrayList<>()).add(node);
            }
        });
    }

    public Integer count() {
        Map<Integer, DominionDTO> currentIdDominions = snapshot.dominions();
        return currentIdDominions != null ? currentIdDominions.size() : 0;
    }

    public List<DominionDTO> getDominionsByWorld(@NotNull World world) {
        List<DominionDTO> dominions = new ArrayList<>();

        for (DominionDTO dominion : getAllDominions()) {
            if (dominion.getWorld() == null) {
                continue;
            }
            if (dominion.getWorld().getUID().equals(world.getUID())) {
                dominions.add(dominion);
            }
        }

        return dominions;
    }
}
