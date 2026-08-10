package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.ServerCache;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownGroupDOO;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
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
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public static @NotNull TownGroupDOO createGroup(@NotNull TownDOO town,
                                                     @NotNull String name,
                                                     @NotNull String displayName) throws Exception {
        int id = transaction(connection -> {
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

            try (PreparedStatement outposts = connection.prepareStatement(
                    "SELECT id FROM dominion WHERE town_id = ?")) {
                outposts.setInt(1, town.getId());
                try (ResultSet rows = outposts.executeQuery()) {
                    while (rows.next()) {
                        ensureMirror(connection, rows.getInt("id"), townGroupId, name, displayName);
                    }
                }
            }
            return townGroupId;
        });
        reloadCaches();
        TownGroupDOO group = TownGroupDOO.select(id);
        if (group == null) throw new SQLException("Inserted town group disappeared: " + id);
        return group;
    }

    public static void deleteGroup(@NotNull TownGroupDOO group) throws Exception {
        transaction(connection -> {
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

    public static void assignMember(@NotNull TownGroupDOO group, @NotNull UUID player) throws Exception {
        transaction(connection -> {
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
            Map<Integer, Integer> mirrorByTownGroup = new LinkedHashMap<>();
            try (PreparedStatement groups = connection.prepareStatement(
                    "SELECT id, name, display_name FROM town_group WHERE town_id = ? ORDER BY priority DESC, id")) {
                groups.setInt(1, town.getId());
                try (ResultSet rows = groups.executeQuery()) {
                    while (rows.next()) {
                        int townGroupId = rows.getInt("id");
                        String name = rows.getString("name");
                        String display = rows.getString("display_name");
                        if (display == null || display.isBlank()) display = name;
                        mirrorByTownGroup.put(townGroupId,
                                ensureMirror(connection, outpost.getId(), townGroupId, name, display));
                    }
                }
            }
            try (PreparedStatement members = connection.prepareStatement(
                    "SELECT player_uuid, group_id FROM town_member WHERE town_id = ? AND group_id > 0")) {
                members.setInt(1, town.getId());
                try (ResultSet rows = members.executeQuery()) {
                    while (rows.next()) {
                        Integer mirrorId = mirrorByTownGroup.get(rows.getInt("group_id"));
                        if (mirrorId == null) continue;
                        ensureMirrorMember(connection, outpost.getId(), mirrorId,
                                UUID.fromString(rows.getString("player_uuid")));
                    }
                }
            }
            return null;
        });
        reloadCaches();
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
