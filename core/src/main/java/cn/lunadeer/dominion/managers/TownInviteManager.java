package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import cn.lunadeer.dominion.utils.databse.DatabaseType;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, short-lived town invitations. The database is the source of truth;
 * the per-server map only suppresses duplicate notifications.
 */
public final class TownInviteManager {
    private static final long INVITE_TTL_MS = 5 * 60 * 1000L;
    private static final Map<UUID, Map<Integer, NotifiedInvite>> notified = new ConcurrentHashMap<>();

    private TownInviteManager() {
    }

    public record PendingInvite(int townId, @NotNull UUID playerUuid,
                                @NotNull Timestamp expiresAt,
                                @NotNull String townName) {
    }

    private record NotifiedInvite(long expiresAt, @NotNull String townName) {
    }

    /** Replace an existing invitation and return its new expiry. */
    public static @NotNull PendingInvite sendInvite(int townId, @NotNull UUID invitee,
                                                    @NotNull UUID inviter,
                                                    @NotNull String townName) throws SQLException {
        Timestamp expiresAt = null;
        try (Connection connection = DatabaseManager.instance.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // Use the shared database clock so all backends agree on the
                // five-minute lifetime even when their JVM clocks drift.
                Timestamp databaseNow;
                try (PreparedStatement clock = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
                     ResultSet rows = clock.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Database did not return CURRENT_TIMESTAMP.");
                    databaseNow = rows.getTimestamp(1);
                }

                // MySQL and PostgreSQL/SQLite expose different upsert syntax.
                // Both forms update the existing row atomically, so two
                // backends inviting the same player cannot race a DELETE and
                // then fail on the unique key.
                String upsertSql = DatabaseManager.instance.getType() == DatabaseType.MYSQL
                        ? "INSERT INTO town_invite(town_id, player_uuid, inviter_uuid, expires_at) VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE inviter_uuid = VALUES(inviter_uuid),"
                        + " expires_at = VALUES(expires_at), created_at = CURRENT_TIMESTAMP"
                        : "INSERT INTO town_invite(town_id, player_uuid, inviter_uuid, expires_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (town_id, player_uuid) DO UPDATE SET inviter_uuid = excluded.inviter_uuid,"
                        + " expires_at = excluded.expires_at, created_at = CURRENT_TIMESTAMP";
                long expiryMillis = (databaseNow.getTime() / 1000L) * 1000L + INVITE_TTL_MS;
                expiresAt = new Timestamp(expiryMillis);
                try (PreparedStatement insert = connection.prepareStatement(upsertSql)) {
                    insert.setInt(1, townId);
                    insert.setString(2, invitee.toString());
                    insert.setString(3, inviter.toString());
                    insert.setTimestamp(4, expiresAt);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof SQLException sqlException) throw sqlException;
                throw new SQLException("Failed to save town invitation.", e);
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
        if (expiresAt == null) throw new SQLException("Failed to calculate invitation expiry.");
        return new PendingInvite(townId, invitee, expiresAt, townName);
    }

    public static boolean hasInvite(int townId, @NotNull UUID player) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM town_invite WHERE town_id = ? AND player_uuid = ? "
                             + "AND expires_at > CURRENT_TIMESTAMP")) {
            statement.setInt(1, townId);
            statement.setString(2, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Consume an invitation on the same JDBC connection as membership insert.
     * The affected-row count makes this safe against concurrent acceptance.
     */
    public static boolean consumeInvite(@NotNull Connection connection, int townId,
                                         @NotNull UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM town_invite WHERE town_id = ? AND player_uuid = ? "
                        + "AND expires_at > CURRENT_TIMESTAMP")) {
            statement.setInt(1, townId);
            statement.setString(2, player.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public static void deleteByTown(int townId) throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM town_invite WHERE town_id = ?")) {
            statement.setInt(1, townId);
            statement.executeUpdate();
        }
        clearNotifiedTown(townId);
    }

    static void clearNotifiedTown(int townId) {
        notified.values().forEach(byTown -> byTown.remove(townId));
    }

    public static void clearNotified(@NotNull UUID player, int townId) {
        Map<Integer, NotifiedInvite> byTown = notified.get(player);
        if (byTown != null) byTown.remove(townId);
    }

    public static void clearPlayer(@NotNull UUID player) {
        notified.remove(player);
    }

    /** Notify the invitee immediately when they are online on this backend. */
    public static void notifyIfOnline(@NotNull PendingInvite invite) {
        Scheduler.runTask(() -> {
            Player player = Bukkit.getPlayer(invite.playerUuid());
            if (player != null) deliver(player, invite);
        });
    }

    /** Query one player's pending invitations after login. */
    public static void notifyPendingAsync(@NotNull UUID player) {
        Scheduler.runTaskAsync(() -> {
            try {
                List<PendingInvite> pending = findPending(List.of(player));
                Scheduler.runTask(() -> deliverPending(pending));
            } catch (Exception e) {
                XLogger.error("Failed to query pending town invitations: {0}", e.getMessage());
            }
        });
    }

    /** Query all online players in one batch for the periodic poll. */
    public static void notifyPendingAsync(@NotNull Collection<UUID> players) {
        List<UUID> snapshot = List.copyOf(players);
        Scheduler.runTaskAsync(() -> {
            try {
                cleanupExpired();
                if (snapshot.isEmpty()) return;
                List<PendingInvite> pending = findPending(snapshot);
                Scheduler.runTask(() -> deliverPending(pending));
            } catch (Exception e) {
                XLogger.error("Failed to poll pending town invitations: {0}", e.getMessage());
            }
        });
    }

    private static void deliverPending(@NotNull List<PendingInvite> pending) {
        for (PendingInvite invite : pending) {
            Player player = Bukkit.getPlayer(invite.playerUuid());
            if (player != null) deliver(player, invite);
        }
    }

    private static void deliver(@NotNull Player player, @NotNull PendingInvite invite) {
        Map<Integer, NotifiedInvite> byTown = notified.computeIfAbsent(
                player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        long expiry = invite.expiresAt().getTime();
        NotifiedInvite previous = byTown.get(invite.townId());
        // An older asynchronous poll must never be allowed to overwrite a
        // newer invite. Equal-expiry rows can still represent a town rename,
        // so a changed name is delivered once to refresh the command hint.
        if (previous != null && (previous.expiresAt() > expiry
                || (previous.expiresAt() == expiry
                && previous.townName().equals(invite.townName())))) return;
        byTown.put(invite.townId(), new NotifiedInvite(expiry, invite.townName()));
        Notification.info(player, Language.townCommandText.inviteReceived, invite.townName());
    }

    private static void cleanupExpired() throws SQLException {
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM town_invite WHERE expires_at <= CURRENT_TIMESTAMP")) {
            statement.executeUpdate();
        }
    }

    private static @NotNull List<PendingInvite> findPending(@NotNull Collection<UUID> players) throws SQLException {
        if (players.isEmpty()) return List.of();
        String placeholders = String.join(", ", Collections.nCopies(players.size(), "?"));
        String sql = "SELECT i.town_id, i.player_uuid, i.expires_at, t.name "
                + "FROM town_invite i JOIN town t ON t.id = i.town_id "
                + "WHERE i.player_uuid IN (" + placeholders + ") "
                + "AND i.expires_at > CURRENT_TIMESTAMP AND t.status <> 'DISBANDED'";
        List<PendingInvite> result = new ArrayList<>();
        try (Connection connection = DatabaseManager.instance.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (UUID player : players) statement.setString(index++, player.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PendingInvite(rows.getInt(1), UUID.fromString(rows.getString(2)),
                            rows.getTimestamp(3), rows.getString(4)));
                }
            }
        }
        return result;
    }
}
