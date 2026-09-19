package cn.lunadeer.dominion.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Shared SQL used within the caller's land lifecycle transaction. */
final class TownLandJdbc {
    private TownLandJdbc() {}

    static String forUpdate(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite")
                ? "" : " FOR UPDATE";
    }

    static int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement.executeUpdate();
        }
    }

    static void archive(Connection connection, String source, String destination, String key, int id,
                        String townColumn, Integer townId, String reason) throws SQLException {
        List<String> columns = new ArrayList<>();
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + source + " WHERE 1 = 0");
             ResultSet rows = statement.executeQuery()) {
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                String name = rows.getMetaData().getColumnName(i);
                columns.add(quote.isEmpty() ? name : quote + name.replace(quote, quote + quote) + quote);
            }
        }
        String joined = String.join(", ", columns);
        execute(connection, "INSERT INTO " + destination + " (" + joined + ", " + townColumn
                        + ", archived_at, archive_reason) SELECT " + joined
                        + ", ?, CURRENT_TIMESTAMP, ? FROM " + source + " WHERE " + key + " = ?",
                townId == null ? -1 : townId, reason, id);
    }

    static void clearTownPermissions(Connection connection, int dominionId) throws SQLException {
        execute(connection, "DELETE FROM town_group_outpost_override WHERE dominion_id = ?", dominionId);
        execute(connection, "DELETE FROM town_trust WHERE scope_type = 'OUTPOST' AND scope_id = ?", dominionId);
        execute(connection, "DELETE FROM dominion_member WHERE dom_id = ?", dominionId);
        execute(connection, "DELETE FROM dominion_group WHERE dom_id = ? "
                + "AND (town_group_id IS NOT NULL OR name = ?)", dominionId, TownManager.TOWN_MEMBER_GROUP_NAME);
    }
}
