package cn.lunadeer.dominion.managers.teleport;

import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.DatabaseType;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldLong;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.syntax.Alter.Alter;
import cn.lunadeer.dominion.utils.databse.syntax.Table.Column;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** One-shot best-effort handoff. Call only on an IO worker, never from join/player events. */
public final class TeleportHandoffStore {
    public record Handoff(UUID playerId, UUID requestId, int dominionId, int serverId, long expiresAt) { }

    /** Startup-only, additive and idempotent. Legacy rows remain invalid until purged. */
    public static void migrate() throws SQLException {
        Alter.alter().table("tp_cache").add().column(Column.of(new FieldString("request_id"))).execute();
        Alter.alter().table("tp_cache").add().column(Column.of(new FieldInteger("target_server_id"))).execute();
        Alter.alter().table("tp_cache").add().column(Column.of(new FieldLong("expires_at"))).execute();
    }

    public void save(Handoff handoff) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            save(connection, DatabaseManager.instance.getType(), handoff);
        }
    }

    static void save(Connection connection, DatabaseType type, Handoff value) throws SQLException {
        // Never let a late, cancelled writer overwrite a newer request for this player.
        String conflict = type == DatabaseType.MYSQL
                ? " ON DUPLICATE KEY UPDATE uuid=VALUES(uuid)" : " ON CONFLICT(uuid) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO tp_cache(uuid,dom_id,request_id,target_server_id,expires_at) VALUES(?,?,?,?,?)" + conflict)) {
            statement.setString(1, value.playerId.toString());
            statement.setInt(2, value.dominionId);
            statement.setString(3, value.requestId.toString());
            statement.setInt(4, value.serverId);
            statement.setLong(5, value.expiresAt);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT request_id FROM tp_cache WHERE uuid=?")) {
            statement.setString(1, value.playerId.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next() || !value.requestId.toString().equals(result.getString(1))) {
                    throw new SQLException("Another cross-server teleport is pending for " + value.playerId);
                }
            }
        }
    }

    public Handoff claim(UUID player, int serverId, long now) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            return claim(connection, player, serverId, now);
        }
    }

    static Handoff claim(Connection connection, UUID player, int serverId, long now) throws SQLException {
        Handoff value;
        try (PreparedStatement statement = connection.prepareStatement("SELECT dom_id,request_id,expires_at FROM tp_cache "
                + "WHERE uuid=? AND target_server_id=? AND expires_at>? AND request_id IS NOT NULL")) {
            statement.setString(1, player.toString());
            statement.setInt(2, serverId);
            statement.setLong(3, now);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return null;
                value = new Handoff(player, UUID.fromString(result.getString("request_id")),
                        result.getInt("dom_id"), serverId, result.getLong("expires_at"));
            }
        }
        // Autocommit conditional delete is the claim; two arrivals cannot both win the same request.
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM tp_cache "
                + "WHERE uuid=? AND request_id=? AND target_server_id=? AND expires_at>?")) {
            statement.setString(1, player.toString());
            statement.setString(2, value.requestId.toString());
            statement.setInt(3, serverId);
            statement.setLong(4, now);
            return statement.executeUpdate() == 1 ? value : null;
        }
    }

    public void remove(UUID player, UUID request) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            remove(connection, player, request);
        }
    }

    static int remove(Connection connection, UUID player, UUID request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM tp_cache WHERE uuid=? AND request_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, request.toString());
            return statement.executeUpdate();
        }
    }

    public void purge(long now) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM tp_cache "
                     + "WHERE expires_at IS NULL OR request_id IS NULL OR target_server_id IS NULL OR expires_at<=?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }
}
