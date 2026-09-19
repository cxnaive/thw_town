package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static cn.lunadeer.dominion.managers.TownLandJdbc.*;

/** One transaction for disband, sharing town/land locks with attachment and force transfer. */
final class TownDisbandManager {
    private TownDisbandManager() {}

    record Result(List<DominionDOO> deleted, List<DominionDOO> returned) {}
    private record Row(int id, int parentId, Integer townId, boolean contributed) {}

    static Result disband(int townId, boolean dissolveAll, @Nullable UUID requiredMayor) throws SQLException {
        Result result = null;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            result = disband(connection, townId, dissolveAll, requiredMayor);
        } catch (SQLException e) {
            if (result == null) throw e;
            XLogger.warn("Town disband committed, but closing the connection failed: {0}", e.getMessage());
        }
        return result;
    }

    static Result disband(Connection connection, int townId, boolean dissolveAll,
                          @Nullable UUID requiredMayor) throws SQLException {
        if (townId <= 0) throw new SQLException("Invalid town id: " + townId);
        boolean oldAutoCommit = connection.getAutoCommit();
        boolean committed = false;
        connection.setAutoCommit(false);
        try {
            TownPermissionManager.lockTown(connection, townId);
            if (requiredMayor != null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT mayor_uuid FROM town WHERE id = ?" + forUpdate(connection))) {
                    statement.setInt(1, townId);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next() || !requiredMayor.toString().equalsIgnoreCase(row.getString(1))) {
                            throw new SQLException("The town mayor changed; disband was cancelled.");
                        }
                    }
                }
            }

            // Current locking reads, not a cached list or an earlier MySQL snapshot.
            List<Row> owned = rows(connection, "town_id", townId);
            Map<Integer, Row> affected = new LinkedHashMap<>();
            ArrayDeque<Row> pending = new ArrayDeque<>(owned);
            while (!pending.isEmpty()) {
                Row row = pending.removeFirst();
                if (affected.putIfAbsent(row.id(), row) != null) continue;
                pending.addAll(rows(connection, "parent_dom_id", row.id()));
            }

            Set<Integer> deleting = new HashSet<>();
            List<DominionDOO> deleted = new ArrayList<>();
            List<DominionDOO> returned = new ArrayList<>();
            for (Row row : owned) {
                DominionDOO snapshot = DominionDOO.selectForUpdate(connection, row.id());
                if (snapshot == null || !Integer.valueOf(townId).equals(snapshot.getTownId())) {
                    throw new SQLException("Outpost changed while disbanding: " + row.id());
                }
                boolean delete = dissolveAll || !row.contributed() || "FOUNDING".equals(snapshot.getClaimKind());
                if (delete) {
                    deleting.add(row.id());
                    deleted.add(snapshot);
                } else {
                    returned.add(snapshot);
                }
                String reason = delete ? "DISBANDED_TOWN" : "RETURNED_TOWN";
                archive(connection, "dominion", "dominion_archive", "id", row.id(),
                        "archived_town_id", townId, reason);
                archive(connection, "dominion_member", "dominion_member_archive", "dom_id", row.id(),
                        "town_id", townId, reason);
            }

            // Foreign/personal branches must not be consumed by a parent FK cascade.
            // Keep the nearest surviving ancestor, so nested land never overlaps
            // an existing ancestor at the top level.
            for (Row row : affected.values()) {
                if (deleting.contains(row.id()) || !deleting.contains(row.parentId())) continue;
                int parent = row.parentId();
                Set<Integer> visited = new HashSet<>(Set.of(row.id()));
                while (deleting.contains(parent)) {
                    if (!visited.add(parent)) throw new SQLException("Cyclic dominion hierarchy.");
                    parent = affected.get(parent).parentId();
                }
                execute(connection, "UPDATE dominion SET parent_dom_id = ? WHERE id = ?", parent, row.id());
            }

            for (DominionDOO land : returned) {
                clearTownPermissions(connection, land.getId());
                if (execute(connection, "UPDATE dominion SET town_id = NULL, attached_by = NULL, attached_at = NULL, "
                                + "outpost_admin_uuid = NULL, town_paid_value = 0 WHERE id = ? AND town_id = ?",
                        land.getId(), townId) != 1) {
                    throw new SQLException("Outpost changed during return: " + land.getId());
                }
            }

            // Delete deepest first to archive every row before its parent cascades.
            Map<Integer, Integer> depths = new HashMap<>();
            for (DominionDOO land : deleted) depths.put(land.getId(), depth(land.getId(), affected));
            deleted.sort(Comparator.comparingInt((DominionDOO land) -> depths.get(land.getId())).reversed());
            for (DominionDOO land : deleted) {
                clearTownPermissions(connection, land.getId());
                execute(connection, "DELETE FROM dominion_group WHERE dom_id = ?", land.getId());
                if (execute(connection, "DELETE FROM dominion WHERE id = ? AND town_id = ?",
                        land.getId(), townId) != 1) {
                    throw new SQLException("Outpost changed during deletion: " + land.getId());
                }
            }

            // Explicit cleanup also supports historical databases without FK enforcement.
            for (String table : List.of("town_member", "town_trust", "town_invite", "town_level_progress",
                    "town_group_flag", "town_group")) {
                execute(connection, "DELETE FROM " + table + " WHERE town_id = ?", townId);
            }
            if (execute(connection, "DELETE FROM town WHERE id = ?", townId) != 1) {
                throw new SQLException("Town changed during disband: " + townId);
            }
            Result result = new Result(List.copyOf(deleted), List.copyOf(returned));
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(oldAutoCommit);
            } catch (SQLException cleanupError) {
                if (!committed) throw cleanupError;
                XLogger.warn("Town disband committed, but connection cleanup failed: {0}", cleanupError.getMessage());
            }
        }
    }

    private static List<Row> rows(Connection connection, String column, int value) throws SQLException {
        List<Row> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, parent_dom_id, town_id, attached_by FROM dominion WHERE " + column
                        + " = ? AND id > 0 ORDER BY id" + forUpdate(connection))) {
            statement.setInt(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int town = rows.getInt("town_id");
                    Integer townId = rows.wasNull() ? null : town;
                    result.add(new Row(rows.getInt("id"), rows.getInt("parent_dom_id"), townId,
                            rows.getString("attached_by") != null));
                }
            }
        }
        return result;
    }

    private static int depth(int id, Map<Integer, Row> rows) throws SQLException {
        Set<Integer> visited = new HashSet<>();
        while (rows.containsKey(id)) {
            if (!visited.add(id)) throw new SQLException("Cyclic dominion hierarchy.");
            id = rows.get(id).parentId();
        }
        return visited.size();
    }
}
