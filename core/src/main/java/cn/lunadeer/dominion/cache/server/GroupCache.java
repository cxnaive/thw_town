package cn.lunadeer.dominion.cache.server;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.doos.GroupDOO;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GroupCache extends Cache {

    private final Integer serverId;
    private volatile ConcurrentHashMap<Integer, GroupDTO> idGroups;            // Group ID -> GroupDTO
    private volatile ConcurrentHashMap<Integer, CopyOnWriteArrayList<Integer>> dominionGroupsMap;  // Dominion ID -> Groups ID

    public GroupCache(Integer serverId) {
        this.serverId = serverId;
    }

    public void reloadNow() {
        try {
            loadExecution();
        } catch (Exception e) {
            cn.lunadeer.dominion.utils.XLogger.error(e);
        }
    }

    /** Refresh one group immediately without scheduling a full cache reload. */
    public void reloadNow(Integer groupId) {
        try {
            loadExecution(groupId);
        } catch (Exception e) {
            cn.lunadeer.dominion.utils.XLogger.error(e);
        }
    }

    /** Refresh several groups with one database query. */
    public void reloadNow(Collection<Integer> groupIds) {
        if (groupIds.isEmpty()) return;
        try {
            List<GroupDOO> groups = GroupDOO.select(groupIds);
            Map<Integer, GroupDOO> selected = new HashMap<>();
            for (GroupDOO group : groups) selected.put(group.getId(), group);
            synchronized (this) {
                ensureInitialized();
                for (Integer groupId : groupIds) {
                    GroupDOO group = selected.get(groupId);
                    if (group == null) {
                        removeCachedGroup(groupId);
                    } else {
                        putCachedGroup(group);
                    }
                }
            }
        } catch (Exception e) {
            cn.lunadeer.dominion.utils.XLogger.error(e);
        }
    }

    public @Nullable GroupDTO getGroup(Integer id) {
        ConcurrentHashMap<Integer, GroupDTO> currentGroups = idGroups;
        return currentGroups != null ? currentGroups.get(id) : null;
    }

    public @NotNull List<GroupDTO> getDominionGroups(DominionDTO dominion) {
        if (dominion == null) return new ArrayList<>();
        return getDominionGroups(dominion.getId());
    }

    public @NotNull List<GroupDTO> getDominionGroups(Integer dominionId) {
        ConcurrentHashMap<Integer, CopyOnWriteArrayList<Integer>> currentDominionGroupsMap = dominionGroupsMap;
        ConcurrentHashMap<Integer, GroupDTO> currentIdGroups = idGroups;

        if (currentDominionGroupsMap == null || currentIdGroups == null ||
            !currentDominionGroupsMap.containsKey(dominionId)) {
            return List.of();
        }

        List<GroupDTO> groups = new ArrayList<>();
        CopyOnWriteArrayList<Integer> groupIds = currentDominionGroupsMap.get(dominionId);
        if (groupIds != null) {
            groupIds.forEach(groupId -> {
                GroupDTO group = currentIdGroups.get(groupId);
                if (group != null) {
                    groups.add(group);
                }
            });
        }
        return groups;
    }

    @Override
    void loadExecution() throws Exception {
        // Create temporary maps to avoid race conditions
        ConcurrentHashMap<Integer, GroupDTO> tempIdGroups = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, CopyOnWriteArrayList<Integer>> tempDominionGroupsMap = new ConcurrentHashMap<>();

        List<GroupDOO> allGroups = GroupDOO.select();
        for (GroupDOO group : allGroups) {
            DominionDTO dominion = CacheManager.instance.getDominion(group.getDomID());
            if (dominion == null || !Objects.equals(dominion.getServerId(), serverId)) continue;

            tempIdGroups.put(group.getId(), group);
            tempDominionGroupsMap.computeIfAbsent(dominion.getId(), k -> new CopyOnWriteArrayList<>())
                    .add(group.getId());
        }

        // Atomically replace all cache data
        synchronized (this) {
            idGroups = tempIdGroups;
            dominionGroupsMap = tempDominionGroupsMap;
        }
    }

    @Override
    void loadExecution(Integer idToLoad) throws Exception {
        GroupDTO group = GroupDOO.select(idToLoad);
        if (group == null) {
            // If group doesn't exist, remove it from cache if present
            synchronized (this) {
                removeCachedGroup(idToLoad);
            }
            return;
        }

        // Ensure cache is initialized
        if (idGroups == null || dominionGroupsMap == null) {
            loadExecution();
            return;
        }

        synchronized (this) {
            putCachedGroup(group);
        }
    }

    private void ensureInitialized() throws Exception {
        if (idGroups == null || dominionGroupsMap == null) loadExecution();
    }

    private void putCachedGroup(GroupDTO group) {
        GroupDTO old = idGroups.put(group.getId(), group);
        if (old != null && !Objects.equals(old.getDomID(), group.getDomID())) {
            CopyOnWriteArrayList<Integer> oldGroupList = dominionGroupsMap.get(old.getDomID());
            if (oldGroupList != null) oldGroupList.remove(old.getId());
        }
        dominionGroupsMap.computeIfAbsent(group.getDomID(), k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(group.getId());
    }

    private void removeCachedGroup(Integer groupId) {
        if (idGroups == null) return;
        GroupDTO removed = idGroups.remove(groupId);
        if (removed == null || dominionGroupsMap == null) return;
        CopyOnWriteArrayList<Integer> groupList = dominionGroupsMap.get(removed.getDomID());
        if (groupList != null) groupList.remove(removed.getId());
    }

    @Override
    void deleteExecution(Integer idToDelete) {
        if (idGroups == null || dominionGroupsMap == null) {
            return;
        }

        synchronized (this) {
            GroupDTO group = idGroups.remove(idToDelete);
            if (group != null) {
                CopyOnWriteArrayList<Integer> groupList = dominionGroupsMap.get(group.getDomID());
                if (groupList != null) {
                    groupList.remove(group.getId());
                    // Clean up empty lists to prevent memory leaks
                    if (groupList.isEmpty()) {
                        dominionGroupsMap.remove(group.getDomID());
                    }
                }
            }
        }
    }

    public Integer count() {
        ConcurrentHashMap<Integer, GroupDTO> currentGroups = idGroups;
        return currentGroups != null ? currentGroups.size() : 0;
    }
}
