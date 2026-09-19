package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.ServerCache;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional synchronization between town_group definitions,
 * town_member assignments, and per-outpost dominion_group/member mirrors.
 */
public final class TownGroupSyncHandler {

    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Connection connection) throws Exception;
    }

    private static <T> T transaction(TransactionWork<T> work) throws Exception {
        T result = null;
        boolean committed = false;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                result = work.run(connection);
                connection.commit();
                committed = true;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(oldAutoCommit);
                } catch (SQLException cleanupError) {
                    if (!committed) throw cleanupError;
                    XLogger.warn("Town operation committed, but connection cleanup failed: {0}", cleanupError.getMessage());
                }
            }
        } catch (SQLException closeError) {
            if (!committed) throw closeError;
            XLogger.warn("Town operation committed, but closing the connection failed: {0}", closeError.getMessage());
        }
        return result;
    }

    public static @NotNull TownGroupDOO createGroup(@NotNull TownDOO town,
                                                     @NotNull String name,
                                                     @NotNull String displayName) throws Exception {
        int id = transaction(connection -> {
            TownPermissionManager.lockTown(connection, town.getId());
            int townGroupId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO town_group(town_id, name, display_name, priority) VALUES (?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setInt(1, town.getId());
                statement.setString(2, name);
                statement.setString(3, displayName);
                statement.executeUpdate();
                townGroupId = generatedId(statement);
            }
            if (townGroupId <= 0) {
                townGroupId = queryId(connection,
                        "SELECT id FROM town_group WHERE town_id = ? AND name = ?",
                        town.getId(), name);
            }
            if (townGroupId <= 0) {
                throw new SQLException("Failed to resolve inserted town group id.");
            }

            List<Integer> outpostIds = new ArrayList<>();
            try (PreparedStatement outposts = connection.prepareStatement(
                    "SELECT id FROM dominion WHERE town_id = ?")) {
                outposts.setInt(1, town.getId());
                try (ResultSet rows = outposts.executeQuery()) {
                    while (rows.next()) outpostIds.add(rows.getInt("id"));
                }
            }
            for (Integer outpostId : outpostIds) {
                ensureTownMemberMirror(connection, outpostId);
                ensureMirror(connection, outpostId, townGroupId, name, displayName);
            }
            if (TownPermissionManager.isTemplateMode(connection, town.getId())) {
                TownPermissionManager.initializeGroupTemplate(
                        connection, town.getId(), townGroupId);
                TownPermissionManager.syncTownFlags(connection, town.getId());
            }
            return townGroupId;
        });
        reloadCaches();
        TownGroupDOO group = TownGroupDOO.select(id);
        if (group == null) throw new SQLException("Inserted town group disappeared: " + id);
        return group;
    }

    /**
     * Update the user-facing name while keeping the internal group key stable.
     * Every outpost mirror uses the same display name in name_colored.
     */
    public static void updateDisplayName(@NotNull TownGroupDOO group,
                                         @NotNull String displayName) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, group.getTownId());
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE town_group SET display_name = ? WHERE id = ? AND town_id = ?")) {
                statement.setString(1, displayName);
                statement.setInt(2, group.getId());
                statement.setInt(3, group.getTownId());
                updated = statement.executeUpdate();
            }
            if (updated != 1) {
                throw new SQLException("Town group disappeared while updating its display name: "
                        + group.getId());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE dominion_group SET name_colored = ? WHERE town_group_id = ?")) {
                statement.setString(1, displayName);
                statement.setInt(2, group.getId());
                statement.executeUpdate();
            }
            return null;
        });
        reloadCaches();
    }

    public static void deleteGroup(@NotNull TownGroupDOO group) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, group.getTownId());
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM town_group_outpost_override WHERE town_group_id = ? "
                            + "AND dominion_id IN (SELECT id FROM dominion WHERE town_id = ?)")) {
                statement.setInt(1, group.getId());
                statement.setInt(2, group.getTownId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM town_group_flag WHERE town_id = ? AND town_group_id = ?")) {
                statement.setInt(1, group.getTownId());
                statement.setInt(2, group.getId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM dominion_member WHERE group_id IN "
                            + "(SELECT id FROM dominion_group WHERE town_group_id = ?)")) {
                statement.setInt(1, group.getId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM dominion_group WHERE town_group_id = ?")) {
                statement.setInt(1, group.getId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE town_member SET group_id = -1 WHERE town_id = ? AND group_id = ?")) {
                statement.setInt(1, group.getTownId());
                statement.setInt(2, group.getId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM town_group WHERE id = ? AND town_id = ?")) {
                statement.setInt(1, group.getId());
                statement.setInt(2, group.getTownId());
                statement.executeUpdate();
            }
            return null;
        });
        reloadCaches();
    }

    /**
     * Remove every town-owned compatibility row before an outpost is
     * returned to its contributor. Ordinary personal groups are preserved.
     */
    public static void releaseOutpost(int dominionId) throws Exception {
        releaseOutpost(dominionId, null);
    }

    /**
     * Release with an optional mayor constraint. A null mayor UUID denotes a
     * trusted internal/admin operation. Otherwise the current mayor and the
     * contributed-land marker are checked again while holding the town lock.
     */
    public static void releaseOutpost(int dominionId,
                                      @Nullable UUID requiredMayorUuid) throws Exception {
        int serverId = transaction(connection -> {
            int owningServer;
            int townId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT town_id FROM dominion WHERE id = ? AND town_id IS NOT NULL")) {
                statement.setInt(1, dominionId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Outpost disappeared during release: " + dominionId);
                    }
                    townId = row.getInt(1);
                }
            }
            TownPermissionManager.lockTown(connection, townId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT server_id, claim_kind, attached_by FROM dominion WHERE id = ? AND town_id = ?")) {
                statement.setInt(1, dominionId);
                statement.setInt(2, townId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Outpost disappeared during release: " + dominionId);
                    }
                    owningServer = row.getInt("server_id");
                    if ("FOUNDING".equals(row.getString("claim_kind"))) {
                        throw new DominionException(
                                Language.townManagerText.releaseFoundingDenied);
                    }
                    if (requiredMayorUuid != null && row.getString("attached_by") == null) {
                        throw new DominionException(
                                Language.townManagerText.releaseMayorContributedOnly);
                    }
                }
            }
            if (requiredMayorUuid != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT mayor_uuid FROM town WHERE id = ?")) {
                    statement.setInt(1, townId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next() || !requiredMayorUuid.toString().equalsIgnoreCase(
                                row.getString("mayor_uuid"))) {
                            throw new DominionException(
                                    Language.townManagerText.releaseMayorContributedOnly);
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM town_group_outpost_override WHERE dominion_id = ?")) {
                statement.setInt(1, dominionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM dominion_member WHERE dom_id = ?")) {
                statement.setInt(1, dominionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM dominion_group WHERE dom_id = ? "
                            + "AND (town_group_id IS NOT NULL OR name = ?)")) {
                statement.setInt(1, dominionId);
                statement.setString(2, TownManager.TOWN_MEMBER_GROUP_NAME);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM town_trust WHERE scope_type = 'OUTPOST' AND scope_id = ?")) {
                statement.setInt(1, dominionId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE dominion SET town_id = NULL, attached_by = NULL, attached_at = NULL, "
                            + "outpost_admin_uuid = NULL, town_paid_value = 0 "
                            + "WHERE id = ? AND town_id IS NOT NULL")) {
                statement.setInt(1, dominionId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Outpost disappeared during release: " + dominionId);
                }
            }
            return owningServer;
        });
        reloadCaches();
        ServerCache owningCache = CacheManager.instance.getCache().getServerId() == serverId
                ? CacheManager.instance.getCache()
                : CacheManager.instance.getOtherServerCaches().get(serverId);
        if (owningCache != null) {
            owningCache.getDominionCache().load(dominionId);
        }
    }

    public static void assignMember(@NotNull TownGroupDOO group, @NotNull UUID player) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, group.getTownId());
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE town_member SET group_id = ? WHERE town_id = ? AND player_uuid = ?")) {
                statement.setInt(1, group.getId());
                statement.setInt(2, group.getTownId());
                statement.setString(3, player.toString());
                updated = statement.executeUpdate();
            }
            if (updated != 1) {
                throw new SQLException("Target is not a member of town " + group.getTownId());
            }
            deleteMirrorMemberships(connection, group.getTownId(), player);

            try (PreparedStatement mirrors = connection.prepareStatement(
                    "SELECT dg.id, dg.dom_id FROM dominion_group dg "
                            + "JOIN dominion d ON d.id = dg.dom_id "
                            + "WHERE d.town_id = ? AND dg.town_group_id = ?")) {
                mirrors.setInt(1, group.getTownId());
                mirrors.setInt(2, group.getId());
                try (ResultSet rows = mirrors.executeQuery()) {
                    while (rows.next()) {
                        ensureMirrorMember(connection, rows.getInt("dom_id"),
                                rows.getInt("id"), player);
                    }
                }
            }
            return null;
        });
        reloadCaches();
    }

    public static void removeMemberAssignment(int townId, @NotNull UUID player) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, townId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE town_member SET group_id = -1 WHERE town_id = ? AND player_uuid = ?")) {
                statement.setInt(1, townId);
                statement.setString(2, player.toString());
                statement.executeUpdate();
            }
            deleteMirrorMemberships(connection, townId, player);
            return null;
        });
        reloadCaches();
    }

    /** Remove only mirror rows when a formal town membership is deleted. */
    public static void removeDepartedMember(int townId, @NotNull UUID player) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, townId);
            deleteMirrorMemberships(connection, townId, player);
            return null;
        });
        reloadCaches();
    }

    /**
     * Mirror every town group and current assignment onto a newly attached
     * outpost. Safe to run repeatedly.
     */
    public static void syncOutpost(@NotNull TownDOO town, @NotNull DominionDOO outpost) throws Exception {
        transaction(connection -> {
            TownPermissionManager.lockTown(connection, town.getId());
            TownPermissionManager.requireOutpostOfTown(
                    connection, town.getId(), outpost.getId());
            syncOutpost(connection, town.getId(), outpost.getId());
            return null;
        });
        reloadCaches();
    }

    /**
     * Create, charge, attach and initialize a remote town outpost atomically.
     * No partially-created personal dominion or incomplete permission mirror
     * can survive a rollback or process interruption before commit.
     */
    public static @NotNull DominionDOO createRemoteOutpost(
            @NotNull TownDOO town,
            @NotNull UUID owner,
            @NotNull String name,
            @NotNull UUID worldUid,
            @NotNull CuboidDTO cuboid,
            long paidValueCents) throws Exception {
        if (paidValueCents < 0) {
            throw new IllegalArgumentException("paidValueCents must be >= 0");
        }
        int dominionId = transaction(connection -> {
            TownPermissionManager.lockTown(connection, town.getId());
            assertOutpostCapacity(connection, town.getId(), Map.of(worldUid, 1));
            if (paidValueCents > 0) {
                int charged;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE town SET balance = balance - ? "
                                + "WHERE id = ? AND balance >= ?")) {
                    statement.setLong(1, paidValueCents);
                    statement.setInt(2, town.getId());
                    statement.setLong(3, paidValueCents);
                    charged = statement.executeUpdate();
                }
                if (charged != 1) {
                    throw new DominionException(
                            Language.townManagerText.insufficientTownFunds,
                            town.getName(), paidValueCents / 100.0,
                            townBalanceCents(connection, town.getId()) / 100.0);
                }
            }

            int insertedId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO dominion (owner, name, world_uid, "
                            + "x1, y1, z1, x2, y2, z2, parent_dom_id, "
                            + "join_message, leave_message, tp_location, server_id, "
                            + "town_id, claim_kind, town_paid_value) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, -1, ?, ?, 'default', ?, ?, 'NORMAL', ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, owner.toString());
                statement.setString(2, name);
                statement.setString(3, worldUid.toString());
                statement.setInt(4, cuboid.x1());
                statement.setInt(5, cuboid.y1());
                statement.setInt(6, cuboid.z1());
                statement.setInt(7, cuboid.x2());
                statement.setInt(8, cuboid.y2());
                statement.setInt(9, cuboid.z2());
                statement.setString(10, Configuration.pluginMessage.defaultEnterMessage);
                statement.setString(11, Configuration.pluginMessage.defaultLeaveMessage);
                statement.setInt(12, Configuration.multiServer.serverId);
                statement.setInt(13, town.getId());
                statement.setLong(14, paidValueCents);
                statement.executeUpdate();
                insertedId = generatedId(statement);
            }
            if (insertedId <= 0) {
                throw new SQLException("Failed to resolve inserted dominion id.");
            }
            syncOutpost(connection, town.getId(), insertedId);
            return insertedId;
        });

        reloadCaches();
        CacheManager.instance.getCache().getDominionCache().load(dominionId);
        DominionDOO created = DominionDOO.select(dominionId);
        if (created == null) {
            throw new SQLException("Inserted dominion disappeared: " + dominionId);
        }
        return created;
    }

    /**
     * Atomically reserve capacity, convert legacy members, attach one or more
     * existing dominions and initialize their town permission mirrors. Town
     * and candidate rows are locked in a stable order, so capacity, ownership
     * and attachment state cannot change between validation and commit.
     */
    public static @NotNull List<Integer> attachOutposts(
            @NotNull TownDOO town,
            @NotNull List<DominionDOO> candidates,
            @NotNull UUID operator,
            @Nullable UUID expectedOwner) throws Exception {
        if (candidates.isEmpty()) return List.of();
        AttachResult result = transaction(connection -> {
            TownPermissionManager.lockTown(connection, town.getId());

            List<AttachCandidate> available = new ArrayList<>();
            Map<UUID, Integer> additionsByWorld = new LinkedHashMap<>();
            List<Integer> candidateIds = candidates.stream()
                    .map(DominionDOO::getId)
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Integer candidateId : candidateIds) {
                // Portable row lock: MySQL/PostgreSQL lock the updated row;
                // SQLite serializes writers. Stable id order avoids batch
                // deadlocks when two towns contend for overlapping land.
                try (PreparedStatement lock = connection.prepareStatement(
                        "UPDATE dominion SET town_id = town_id "
                                + "WHERE id = ? AND town_id IS NULL")) {
                    lock.setInt(1, candidateId);
                    // Do not inspect the affected-row count here.  MySQL may
                    // report zero for a matched no-op UPDATE when
                    // useAffectedRows=true even though the row was locked.
                    lock.executeUpdate();
                }
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT world_uid, owner, name, server_id FROM dominion "
                                + "WHERE id = ? AND town_id IS NULL")) {
                    select.setInt(1, candidateId);
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) continue;
                        UUID owner = UUID.fromString(row.getString("owner"));
                        if (expectedOwner != null && !expectedOwner.equals(owner)) {
                            throw new SQLException("Dominion " + candidateId
                                    + " changed owner while being attached.");
                        }
                        UUID worldUid = UUID.fromString(row.getString("world_uid"));
                        available.add(new AttachCandidate(candidateId, worldUid, owner,
                                row.getString("name"), row.getInt("server_id")));
                        additionsByWorld.merge(worldUid, 1, Integer::sum);
                    }
                }
            }
            if (available.isEmpty()) return new AttachResult(List.of(), List.of());
            assertOutpostCapacity(connection, town.getId(), additionsByWorld);

            List<Integer> updated = new ArrayList<>();
            List<ConvertedMember> convertedMembers = new ArrayList<>();
            try (PreparedStatement attach = connection.prepareStatement(
                    "UPDATE dominion SET town_id = ?, attached_by = ?, "
                            + "attached_at = CURRENT_TIMESTAMP, town_paid_value = 0 "
                            + "WHERE id = ? AND town_id IS NULL"
                            + (expectedOwner == null ? "" : " AND owner = ?"))) {
                for (AttachCandidate candidate : available) {
                    convertedMembers.addAll(convertMembers(connection, town.getId(), candidate));
                    attach.setInt(1, town.getId());
                    attach.setString(2, operator.toString());
                    attach.setInt(3, candidate.id());
                    if (expectedOwner != null) attach.setString(4, expectedOwner.toString());
                    if (attach.executeUpdate() != 1) {
                        throw new SQLException("Dominion " + candidate.id()
                                + " changed while being attached; retry the operation.");
                    }
                    syncOutpost(connection, town.getId(), candidate.id());
                    updated.add(candidate.id());
                }
            }
            return new AttachResult(updated, convertedMembers);
        });

        // From here on, failures cannot undo attachment or change its result.
        try {
            for (AttachCandidate candidate : candidatesById(result.attachedIds(), candidates)) {
                ServerCache cache = CacheManager.instance.getCache(candidate.serverId());
                if (cache != null) cache.getDominionCache().load(candidate.id());
            }
            reloadCaches();
        } catch (Exception refreshError) {
            XLogger.warn("Outpost attachment committed, but cache refresh failed: {0}", refreshError.getMessage());
        }
        for (ConvertedMember converted : result.convertedMembers()) {
            try {
                Player online = cn.lunadeer.dominion.Dominion.instance.getServer().getPlayer(converted.player());
                if (online != null) {
                    Notification.info(online, Language.townManagerText.mergeMemberTrusted,
                            CacheManager.instance.getPlayerName(converted.player()), converted.dominionName());
                }
            } catch (Exception notificationError) {
                XLogger.warn("Outpost attachment committed, but member notification failed: {0}", notificationError.getMessage());
            }
        }
        return result.attachedIds();
    }

    private static @NotNull List<AttachCandidate> candidatesById(
            @NotNull List<Integer> attachedIds,
            @NotNull List<DominionDOO> candidates) {
        Map<Integer, DominionDOO> byId = new LinkedHashMap<>();
        for (DominionDOO candidate : candidates) byId.put(candidate.getId(), candidate);
        List<AttachCandidate> attached = new ArrayList<>();
        for (Integer id : attachedIds) {
            DominionDOO candidate = byId.get(id);
            if (candidate != null) {
                attached.add(new AttachCandidate(id, candidate.getWorldUid(), candidate.getOwner(),
                        candidate.getName(), candidate.getServerId()));
            }
        }
        return attached;
    }

    private static @NotNull List<ConvertedMember> convertMembers(
            Connection connection, int townId, AttachCandidate candidate) throws SQLException {
        String archiveColumns = memberArchiveColumns();
        try (PreparedStatement archive = connection.prepareStatement(
                "INSERT INTO dominion_member_archive (" + archiveColumns
                        + ", town_id, archived_at, archive_reason) SELECT " + archiveColumns
                        + ", ?, CURRENT_TIMESTAMP, 'MERGED_INTO_TOWN' "
                        + "FROM dominion_member WHERE dom_id = ?")) {
            archive.setInt(1, townId);
            archive.setInt(2, candidate.id());
            archive.executeUpdate();
        }

        List<ConvertedMember> converted = new ArrayList<>();
        List<PriFlag> flags = Flags.getAllPriFlags();
        String flagColumns = String.join(", ", flags.stream().map(PriFlag::getFlagName).toList());
        String selectSql = "SELECT player_uuid" + (flagColumns.isEmpty() ? "" : ", " + flagColumns)
                + " FROM dominion_member WHERE dom_id = ?";
        try (PreparedStatement members = connection.prepareStatement(selectSql)) {
            members.setInt(1, candidate.id());
            try (ResultSet rows = members.executeQuery()) {
                while (rows.next()) {
                    UUID player = UUID.fromString(rows.getString("player_uuid"));
                    if (player.equals(candidate.owner()) || isTownMember(connection, townId, player)) {
                        continue;
                    }
                    boolean build = false;
                    boolean destroy = false;
                    boolean switchFlag = false;
                    boolean itemUse = false;
                    for (PriFlag flag : flags) {
                        if (!rows.getBoolean(flag.getFlagName())) continue;
                        TownManager.TrustCategory category = TownManager.trustCategoryOf(flag);
                        if (category == null) continue;
                        switch (category) {
                            case BUILD -> build = true;
                            case DESTROY -> destroy = true;
                            case SWITCH -> switchFlag = true;
                            case ITEM_USE -> itemUse = true;
                        }
                    }
                    replaceOutpostTrust(connection, townId, player, candidate.id(),
                            build, destroy, switchFlag, itemUse, candidate.owner());
                    converted.add(new ConvertedMember(player, candidate.name()));
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM dominion_member WHERE dom_id = ?")) {
            delete.setInt(1, candidate.id());
            delete.executeUpdate();
        }
        return converted;
    }

    private static boolean isTownMember(Connection connection, int townId,
                                        UUID player) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM town_member WHERE town_id = ? AND player_uuid = ?")) {
            select.setInt(1, townId);
            select.setString(2, player.toString());
            try (ResultSet row = select.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void replaceOutpostTrust(Connection connection, int townId, UUID player,
                                            int dominionId, boolean build, boolean destroy,
                                            boolean switchFlag, boolean itemUse,
                                            UUID createdBy) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM town_trust WHERE town_id = ? AND player_uuid = ? "
                        + "AND scope_type = 'OUTPOST' AND scope_id = ?")) {
            delete.setInt(1, townId);
            delete.setString(2, player.toString());
            delete.setInt(3, dominionId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO town_trust(town_id, player_uuid, scope_type, scope_id, "
                        + "build, destroy, switch, item_use, created_by) "
                        + "VALUES (?, ?, 'OUTPOST', ?, ?, ?, ?, ?, ?)")) {
            insert.setInt(1, townId);
            insert.setString(2, player.toString());
            insert.setInt(3, dominionId);
            insert.setBoolean(4, build);
            insert.setBoolean(5, destroy);
            insert.setBoolean(6, switchFlag);
            insert.setBoolean(7, itemUse);
            insert.setString(8, createdBy.toString());
            insert.executeUpdate();
        }
    }

    private static @NotNull String memberArchiveColumns() {
        List<String> columns = new ArrayList<>(List.of(
                "id", "player_uuid", "dom_id", "group_id"));
        for (PriFlag flag : Flags.getAllPriFlags()) columns.add(flag.getFlagName());
        return String.join(", ", columns);
    }

    private record AttachCandidate(int id, UUID worldUid, UUID owner, String name, int serverId) {
    }

    private record ConvertedMember(UUID player, String dominionName) {
    }

    private record AttachResult(List<Integer> attachedIds,
                                List<ConvertedMember> convertedMembers) {
    }

    /** Check both all-world and per-world limits using the locked DB state. */
    private static void assertOutpostCapacity(Connection connection, int townId,
                                              Map<UUID, Integer> additionsByWorld) throws Exception {
        int level;
        String townName;
        try (PreparedStatement town = connection.prepareStatement(
                "SELECT name, level FROM town WHERE id = ?")) {
            town.setInt(1, townId);
            try (ResultSet row = town.executeQuery()) {
                if (!row.next()) throw new SQLException("Town does not exist: " + townId);
                townName = row.getString("name");
                level = TownManager.effectiveLevel(row.getInt("level"));
            }
        }

        int additions = additionsByWorld.values().stream().mapToInt(Integer::intValue).sum();
        int allWorldsLimit = TownManager.outpostLimit(level, Configuration.town.outpostMaxCount);
        if (allWorldsLimit != -1
                && countOutposts(connection, townId, null) + additions > allWorldsLimit) {
            throw new DominionException(Language.townManagerText.outpostAmountExceeded,
                    townName, level, allWorldsLimit);
        }

        int perWorldLimit = TownManager.outpostLimit(
                level, Configuration.town.outpostMaxCountPerWorld);
        if (perWorldLimit == -1) return;
        for (Map.Entry<UUID, Integer> addition : additionsByWorld.entrySet()) {
            if (countOutposts(connection, townId, addition.getKey()) + addition.getValue()
                    > perWorldLimit) {
                throw new DominionException(Language.townManagerText.outpostAmountExceeded,
                        townName, level, perWorldLimit);
            }
        }
    }

    private static int countOutposts(Connection connection, int townId,
                                     @Nullable UUID worldUid) throws SQLException {
        String sql = worldUid == null
                ? "SELECT COUNT(*) FROM dominion WHERE town_id = ?"
                : "SELECT COUNT(*) FROM dominion WHERE town_id = ? AND world_uid = ?";
        try (PreparedStatement count = connection.prepareStatement(sql)) {
            count.setInt(1, townId);
            if (worldUid != null) count.setString(2, worldUid.toString());
            try (ResultSet row = count.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private static void syncOutpost(Connection connection, int townId,
                                    int dominionId) throws SQLException {
        ensureTownMemberMirror(connection, dominionId);
        Map<Integer, Integer> mirrorByTownGroup = new LinkedHashMap<>();
        try (PreparedStatement groups = connection.prepareStatement(
                "SELECT id, name, display_name FROM town_group "
                        + "WHERE town_id = ? ORDER BY priority DESC, id")) {
            groups.setInt(1, townId);
            try (ResultSet rows = groups.executeQuery()) {
                while (rows.next()) {
                    int townGroupId = rows.getInt("id");
                    String name = rows.getString("name");
                    String display = rows.getString("display_name");
                    if (display == null || display.isBlank()) display = name;
                    mirrorByTownGroup.put(townGroupId,
                            ensureMirror(connection, dominionId, townGroupId, name, display));
                }
            }
        }
        try (PreparedStatement members = connection.prepareStatement(
                "SELECT player_uuid, group_id FROM town_member "
                        + "WHERE town_id = ? AND group_id > 0")) {
            members.setInt(1, townId);
            try (ResultSet rows = members.executeQuery()) {
                while (rows.next()) {
                    Integer mirrorId = mirrorByTownGroup.get(rows.getInt("group_id"));
                    if (mirrorId == null) continue;
                    ensureMirrorMember(connection, dominionId, mirrorId,
                            UUID.fromString(rows.getString("player_uuid")));
                }
            }
        }
        if (TownPermissionManager.isTemplateMode(connection, townId)) {
            TownPermissionManager.syncOutpostFlags(connection, townId, dominionId);
        }
    }

    private static long townBalanceCents(Connection connection, int townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM town WHERE id = ?")) {
            statement.setInt(1, townId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Town does not exist: " + townId);
                return row.getLong(1);
            }
        }
    }

    private static int ensureMirror(Connection connection, int dominionId, int townGroupId,
                                    String name, String displayName) throws SQLException {
        int existing = queryId(connection,
                "SELECT id FROM dominion_group WHERE dom_id = ? AND town_group_id = ?",
                dominionId, townGroupId);
        if (existing > 0) return existing;

        int id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dominion_group(dom_id, name, name_colored, town_group_id) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, dominionId);
            statement.setString(2, name);
            statement.setString(3, displayName);
            statement.setInt(4, townGroupId);
            statement.executeUpdate();
            id = generatedId(statement);
        }
        return id > 0 ? id : queryId(connection,
                "SELECT id FROM dominion_group WHERE dom_id = ? AND town_group_id = ?",
                dominionId, townGroupId);
    }

    private static int ensureTownMemberMirror(Connection connection,
                                              int dominionId) throws SQLException {
        int existing = queryId(connection,
                "SELECT id FROM dominion_group WHERE dom_id = ? AND name = ?",
                dominionId, TownManager.TOWN_MEMBER_GROUP_NAME);
        if (existing > 0) return existing;
        int id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO dominion_group(dom_id, name, name_colored) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, dominionId);
            statement.setString(2, TownManager.TOWN_MEMBER_GROUP_NAME);
            statement.setString(3, TownManager.TOWN_MEMBER_GROUP_NAME);
            statement.executeUpdate();
            id = generatedId(statement);
        }
        return id > 0 ? id : queryId(connection,
                "SELECT id FROM dominion_group WHERE dom_id = ? AND name = ?",
                dominionId, TownManager.TOWN_MEMBER_GROUP_NAME);
    }

    private static void ensureMirrorMember(Connection connection, int dominionId,
                                           int mirrorGroupId, UUID player) throws SQLException {
        int memberId = queryId(connection,
                "SELECT id FROM dominion_member WHERE dom_id = ? AND player_uuid = ?",
                dominionId, player.toString());
        if (memberId > 0) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE dominion_member SET group_id = ? WHERE id = ?")) {
                update.setInt(1, mirrorGroupId);
                update.setInt(2, memberId);
                update.executeUpdate();
            }
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO dominion_member(player_uuid, dom_id, group_id) VALUES (?, ?, ?)")) {
            insert.setString(1, player.toString());
            insert.setInt(2, dominionId);
            insert.setInt(3, mirrorGroupId);
            insert.executeUpdate();
        }
    }

    private static void deleteMirrorMemberships(Connection connection, int townId,
                                                UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM dominion_member WHERE player_uuid = ? AND group_id IN "
                        + "(SELECT dg.id FROM dominion_group dg JOIN dominion d ON d.id = dg.dom_id "
                        + "WHERE d.town_id = ? AND dg.town_group_id IS NOT NULL)")) {
            statement.setString(1, player.toString());
            statement.setInt(2, townId);
            statement.executeUpdate();
        }
    }

    private static int generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            return keys.next() ? keys.getInt(1) : -1;
        }
    }

    private static int queryId(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    public static void reloadCaches() {
        CacheManager manager = CacheManager.instance;
        manager.getTownCache().reloadNow();
        reloadServerCache(manager.getCache());
        for (ServerCache cache : manager.getOtherServerCaches().values()) {
            reloadServerCache(cache);
        }
    }

    private static void reloadServerCache(ServerCache cache) {
        cache.getGroupCache().reloadNow();
        cache.getMemberCache().reloadNow();
    }

    private TownGroupSyncHandler() {
    }
}
