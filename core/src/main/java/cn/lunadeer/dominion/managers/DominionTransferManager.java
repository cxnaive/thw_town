package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.cache.server.ServerCache;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.bukkit.command.CommandSender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.lunadeer.dominion.managers.TownLandJdbc.archive;
import static cn.lunadeer.dominion.managers.TownLandJdbc.execute;

/** Administrator transfer, including outposts whose town row no longer exists. */
public final class DominionTransferManager {
    private DominionTransferManager() {}

    public static List<Integer> forceTransfer(CommandSender operator, int dominionId, UUID newOwner)
            throws Exception {
        if (!Others.isAdministrator(operator)) {
            throw new DominionException(Language.dominionProviderHandlerText.giveForceAdminOnly);
        }
        List<Integer> transferred = null;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            transferred = transfer(connection, dominionId, newOwner);
        } catch (SQLException e) {
            if (transferred == null) throw e;
            XLogger.warn("Forced transfer committed, but closing the connection failed: {0}", e.getMessage());
        }
        // All ownership/cleanup/archive writes committed together. Cache failures
        // must not turn that into a reported rollback or repeat the operation.
        try {
            TownGroupSyncHandler.reloadCaches();
            CacheManager manager = CacheManager.instance;
            manager.getCache().getDominionCache().load();
            for (ServerCache server : manager.getOtherServerCaches().values()) {
                server.getDominionCache().load();
            }
        } catch (Exception e) {
            XLogger.error("Forced transfer committed, but cache refresh failed: {0}", e.getMessage());
        }
        XLogger.info("Admin {0} force-transferred dominions {1} to {2}; archived as ADMIN_FORCE_TRANSFER.",
                operator.getName(), transferred, newOwner);
        return transferred;
    }

    /** Owns the transaction; package access permits real JDBC regression tests. */
    static List<Integer> transfer(Connection connection, int dominionId, UUID newOwner) throws SQLException {
        if (dominionId <= 0) throw new SQLException("The virtual root cannot be transferred.");
        Row expected = readRow(connection, dominionId, false);
        if (expected == null) throw new SQLException("Dominion does not exist: " + dominionId);
        boolean oldAutoCommit = connection.getAutoCommit();
        boolean committed = false;
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement player = connection.prepareStatement("SELECT 1 FROM player_name WHERE uuid = ?")) {
                player.setString(1, newOwner.toString());
                try (ResultSet row = player.executeQuery()) {
                    if (!row.next()) throw new SQLException("The receiving player is not recorded: " + newOwner);
                }
            }
            // Match the town -> dominion lock order used by attach/sync. Missing
            // town rows are deliberately allowed for administrator recovery.
            if (expected.townId() != null) {
                execute(connection, "UPDATE town SET permission_mode = permission_mode WHERE id = ?", expected.townId());
            }
            Row root = readRow(connection, dominionId, true);
            if (root == null || !Objects.equals(root.townId(), expected.townId())) {
                throw new SQLException("Dominion attachment changed; retry the transfer.");
            }
            List<Row> targets = new ArrayList<>();
            targets.add(root);
            collectPersonalChildren(connection, dominionId, new HashSet<>(Set.of(dominionId)), targets);
            for (Row target : targets) {
                archive(connection, "dominion", "dominion_archive", "id", target.id(),
                        "archived_town_id", target.townId(), "ADMIN_FORCE_TRANSFER");
                if (target.townId() != null) {
                    archive(connection, "dominion_member", "dominion_member_archive", "dom_id", target.id(),
                            "town_id", target.townId(), "ADMIN_FORCE_TRANSFER");
                    execute(connection, "DELETE FROM dominion_member WHERE dom_id = ?", target.id());
                    execute(connection, "DELETE FROM dominion_group WHERE dom_id = ? "
                            + "AND (town_group_id IS NOT NULL OR name = ?)", target.id(), TownManager.TOWN_MEMBER_GROUP_NAME);
                }
                execute(connection, "DELETE FROM town_group_outpost_override WHERE dominion_id = ?", target.id());
                execute(connection, "DELETE FROM town_trust WHERE scope_type = 'OUTPOST' AND scope_id = ?", target.id());
                // Preserve containment as well as coordinates/server/world.
                // Promoting a sub to root would overlap its still-existing parent.
                execute(connection, "UPDATE dominion SET owner = ?, town_id = NULL, attached_by = NULL, "
                                + "attached_at = NULL, outpost_admin_uuid = NULL, town_paid_value = 0, claim_kind = 'NORMAL' "
                                + "WHERE id = ?",
                        newOwner.toString(), target.id());
            }
            connection.commit();
            committed = true;
            return targets.stream().map(Row::id).toList();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(oldAutoCommit);
            } catch (SQLException cleanupError) {
                if (!committed) throw cleanupError;
                XLogger.warn("Forced transfer committed, but connection cleanup failed: {0}", cleanupError.getMessage());
            }
        }
    }

    private record Row(int id, int parentId, Integer townId) {}

    private static Row readRow(Connection connection, int id, boolean lock) throws SQLException {
        boolean sqlite = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite");
        if (lock && sqlite) {
            execute(connection, "UPDATE dominion SET owner = owner WHERE id = ?", id);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, parent_dom_id, town_id FROM dominion WHERE id = ?"
                        + (lock && !sqlite ? " FOR UPDATE" : ""))) {
            statement.setInt(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                int town = row.getInt("town_id");
                Integer townId = row.wasNull() ? null : town;
                return new Row(row.getInt("id"), row.getInt("parent_dom_id"), townId);
            }
        }
    }

    private static void collectPersonalChildren(Connection connection, int parent, Set<Integer> visited,
                                                 List<Row> targets) throws SQLException {
        List<Integer> children = new ArrayList<>();
        boolean sqlite = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM dominion WHERE parent_dom_id = ? ORDER BY id"
                        + (sqlite ? "" : " FOR UPDATE"))) {
            statement.setInt(1, parent);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) children.add(rows.getInt(1));
            }
        }
        for (int id : children) {
            if (id <= 0 || !visited.add(id)) continue;
            Row child = readRow(connection, id, true);
            // A town-owned enclave (and its subtree) keeps its current owner.
            if (child == null || child.parentId() != parent || child.townId() != null) continue;
            targets.add(child);
            collectPersonalChildren(connection, id, visited, targets);
        }
    }

}
