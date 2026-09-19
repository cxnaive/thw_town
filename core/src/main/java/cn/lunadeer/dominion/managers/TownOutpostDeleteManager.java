package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static cn.lunadeer.dominion.managers.TownLandJdbc.*;

/** Deletes exactly one outpost; town and all child dominions survive. */
public final class TownOutpostDeleteManager {
    private TownOutpostDeleteManager() {}

    public static DominionDOO delete(CommandSender operator, DominionDOO expected, boolean confirmed) throws SQLException {
        DominionDOO result = null;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            result = delete(connection, expected.getId(), expected.getTownId(),
                    operator instanceof Player player ? player.getUniqueId() : null,
                    Others.isAdministrator(operator), confirmed);
        } catch (SQLException e) {
            if (result == null) throw e;
            XLogger.warn("Outpost operation completed, but closing the connection failed: {0}", e.getMessage());
        }
        if (confirmed) {
            try {
                TownGroupSyncHandler.reloadCaches();
                var server = CacheManager.instance.getCache(result.getServerId());
                if (server != null) server.getDominionCache().load();
            } catch (Exception refreshError) {
                XLogger.warn("Outpost deletion committed, but cache refresh failed: {0}", refreshError.getMessage());
            }
            XLogger.info("Operator {0} deleted outpost {1} ({2}) from town {3}; archived as DELETED_TOWN_OUTPOST.",
                    operator.getName(), result.getName(), result.getId(), result.getTownId());
        }
        return result;
    }

    static DominionDOO delete(Connection connection, int id, Integer expectedTown, UUID operator,
                               boolean administrator, boolean confirmed) throws SQLException {
        if (id <= 0 || expectedTown == null) throw new SQLException("A town outpost is required.");
        boolean oldAutoCommit = connection.getAutoCommit();
        boolean committed = false;
        connection.setAutoCommit(false);
        try {
            // Same lock order as disband, transfer and attachment. An orphaned
            // town id is allowed for admin cleanup, never for a normal player.
            execute(connection, "UPDATE town SET permission_mode = permission_mode WHERE id = ?", expectedTown);
            DominionDOO current = DominionDOO.selectForUpdate(connection, id);
            if (current == null || !Objects.equals(expectedTown, current.getTownId())) {
                throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
            }
            if (!administrator && !isManager(connection, expectedTown, operator)) {
                throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteDenied);
            }
            if (confirmed) {
                List<Integer> children = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM dominion WHERE parent_dom_id = ? ORDER BY id" + forUpdate(connection))) {
                    statement.setInt(1, id);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) children.add(rows.getInt(1));
                    }
                }
                archive(connection, "dominion", "dominion_archive", "id", id,
                        "archived_town_id", expectedTown, "DELETED_TOWN_OUTPOST");
                archive(connection, "dominion_member", "dominion_member_archive", "dom_id", id,
                        "town_id", expectedTown, "DELETED_TOWN_OUTPOST");
                // Removing this node from the hierarchy preserves all child
                // protection and prevents a foreign-key cascade from deleting it.
                for (int child : children) {
                    execute(connection, "UPDATE dominion SET parent_dom_id = ? WHERE id = ?",
                            current.getParentDomId(), child);
                }
                clearTownPermissions(connection, id);
                execute(connection, "DELETE FROM dominion_group WHERE dom_id = ?", id);
                if (execute(connection, "DELETE FROM dominion WHERE id = ? AND town_id = ?", id, expectedTown) != 1) {
                    throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
                }
            }
            connection.commit();
            committed = true;
            return current;
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
            throw e;
        } finally {
            try { connection.setAutoCommit(oldAutoCommit); }
            catch (SQLException cleanupError) {
                if (!committed) throw cleanupError;
                XLogger.warn("Outpost operation completed, but connection cleanup failed: {0}", cleanupError.getMessage());
            }
        }
    }

    private static boolean isManager(Connection connection, int town, UUID operator) throws SQLException {
        if (operator == null) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT m.role FROM town_member m JOIN town t ON t.id = m.town_id "
                        + "WHERE m.town_id = ? AND m.player_uuid = ?" + forUpdate(connection))) {
            statement.setInt(1, town);
            statement.setString(2, operator.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && ("MAYOR".equals(row.getString(1)) || "ASSISTANT".equals(row.getString(1)));
            }
        }
    }
}
