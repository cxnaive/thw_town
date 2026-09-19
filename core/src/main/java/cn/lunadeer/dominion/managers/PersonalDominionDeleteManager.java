package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static cn.lunadeer.dominion.managers.TownLandJdbc.*;

/** Legacy personal deletion cannot consume a town-owned branch. */
public final class PersonalDominionDeleteManager {
    private PersonalDominionDeleteManager() {}

    public record Result(DominionDOO root, List<DominionDOO> deleted, List<DominionDOO> preserved) {}

    public static Result delete(CommandSender operator, int id, boolean confirmed) throws SQLException {
        boolean admin = Others.isAdministrator(operator);
        if (!admin && !Configuration.landSystem.legacyAllowDelete) {
            throw new DominionException(Language.dominionProviderHandlerText.legacyDeleteDisabled);
        }
        Result result = null;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            result = delete(connection, id, operator instanceof Player player ? player.getUniqueId() : null,
                    admin, confirmed);
        } catch (SQLException error) {
            if (result == null) throw error;
            XLogger.warn("Personal deletion completed, but closing the connection failed: {0}", error.getMessage());
        }
        if (confirmed) {
            try {
                TownGroupSyncHandler.reloadCaches();
                var cache = CacheManager.instance.getCache(result.root().getServerId());
                if (cache != null) cache.getDominionCache().load();
            } catch (Exception error) {
                XLogger.warn("Personal deletion committed, but cache refresh failed: {0}", error.getMessage());
            }
        }
        return result;
    }

    static Result delete(Connection connection, int id, UUID operator, boolean admin, boolean confirmed)
            throws SQLException {
        if (id <= 0) throw new SQLException("The virtual root cannot be deleted.");
        boolean autoCommit = connection.getAutoCommit();
        boolean committed = false;
        connection.setAutoCommit(false);
        try {
            // Obtain the SQLite write lock before any read; on MySQL/PG the
            // following SELECT locks the current row rather than a stale snapshot.
            if (forUpdate(connection).isEmpty()) {
                execute(connection, "UPDATE dominion SET owner = owner WHERE id = ?", id);
            }
            DominionDOO root = DominionDOO.selectForUpdate(connection, id);
            if (root == null || root.isTownOutpost()) {
                throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
            }
            if (!admin && !root.getOwner().equals(operator)) {
                throw new DominionException(Language.assertsText.notOwner, root.getName());
            }
            List<DominionDOO> deleted = new ArrayList<>();
            List<DominionDOO> preserved = new ArrayList<>();
            collect(connection, root, new HashSet<>(), deleted, preserved);
            if (confirmed) {
                for (DominionDOO land : deleted) {
                    archive(connection, "dominion", "dominion_archive", "id", land.getId(),
                            "archived_town_id", null, "DELETED_PERSONAL_DOMINION");
                    archive(connection, "dominion_member", "dominion_member_archive", "dom_id", land.getId(),
                            "town_id", null, "DELETED_PERSONAL_DOMINION");
                }
                // Every ancestor between these branch roots and the selected
                // root is personal and will be removed. Preserve the entire branch.
                for (DominionDOO land : preserved) {
                    execute(connection, "UPDATE dominion SET parent_dom_id = ? WHERE id = ?",
                            root.getParentDomId(), land.getId());
                }
                for (DominionDOO land : deleted) {
                    clearTownPermissions(connection, land.getId());
                    execute(connection, "DELETE FROM dominion_group WHERE dom_id = ?", land.getId());
                    if (execute(connection, "DELETE FROM dominion WHERE id = ? AND town_id IS NULL", land.getId()) != 1) {
                        throw new DominionException(Language.dominionProviderHandlerText.outpostDeleteChanged);
                    }
                }
            }
            Result result = new Result(root, List.copyOf(deleted), List.copyOf(preserved));
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException error) {
            try { connection.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
            throw error;
        } finally {
            try { connection.setAutoCommit(autoCommit); }
            catch (SQLException error) {
                if (!committed) throw error;
                XLogger.warn("Personal deletion completed, but connection cleanup failed: {0}", error.getMessage());
            }
        }
    }

    private static void collect(Connection connection, DominionDOO parent, Set<Integer> visited,
                                List<DominionDOO> deleted, List<DominionDOO> preserved) throws SQLException {
        if (!visited.add(parent.getId())) throw new SQLException("Cyclic dominion hierarchy.");
        List<Integer> children = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM dominion WHERE parent_dom_id = ? ORDER BY id" + forUpdate(connection))) {
            statement.setInt(1, parent.getId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) children.add(rows.getInt(1));
            }
        }
        for (int id : children) {
            DominionDOO child = DominionDOO.selectForUpdate(connection, id);
            if (child == null) throw new SQLException("Child disappeared during deletion.");
            if (child.isTownOutpost()) preserved.add(child);
            else collect(connection, child, visited, deleted, preserved);
        }
        deleted.add(parent); // deepest first: no unarchived FK cascade
    }
}
