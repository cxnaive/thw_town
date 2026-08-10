package cn.lunadeer.dominion.commands;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.doos.PlayerDOO;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Admin utility to fix player UUID mismatches caused by online/offline mode switches.
 * 
 * Usage: /dom admin fix_uuid <player_name> [confirm]
 * 
 * v5.0.3 fixes:
 * 1. Multiple stale UUID detection (name collision safety)
 * 2. FK constraint fix (INSERT player_name before child migration)
 * 3. Conflict deletion tracking and logging
 */
public class UuidFixCommand {

    private static class PlayerRecord {
        int id;
        UUID uuid;
        String name;
        
        PlayerRecord(int id, UUID uuid, String name) {
            this.id = id;
            this.uuid = uuid;
            this.name = name;
        }
    }

    public static void fixUuid(@NotNull CommandSender sender, @NotNull String playerName, boolean confirm) {
        try {
            // Step 1: Get online player (must be online to get correct UUID)
            Player onlinePlayer = Dominion.instance.getServer().getPlayer(playerName);
            if (onlinePlayer == null) {
                throw new DominionException("Player {0} must be online to fix UUID.", playerName);
            }

            UUID correctUuid = onlinePlayer.getUniqueId();

            // Step 2: Find ALL player_name rows for this player name
            List<PlayerRecord> allRecords = new ArrayList<>();
            PlayerRecord correctRecord = null;
            
            try (Connection conn = DatabaseManager.instance.getConnection()) {
                String sql = "SELECT id, uuid, last_known_name FROM player_name WHERE last_known_name = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int id = rs.getInt("id");
                            UUID uuid = UUID.fromString(rs.getString("uuid"));
                            String name = rs.getString("last_known_name");
                            PlayerRecord record = new PlayerRecord(id, uuid, name);
                            allRecords.add(record);
                            
                            if (uuid.equals(correctUuid)) {
                                correctRecord = record;
                            }
                        }
                    }
                }
            }

            // Step 3: Handle cases
            if (allRecords.isEmpty()) {
                Notification.info(sender, "Player {0} not found in database. Creating new record...", playerName);
                PlayerDOO.create(onlinePlayer.getUniqueId(), onlinePlayer.getName());
                Notification.info(sender, "§aCreated new record for {0} with UUID {1}.", playerName, correctUuid);
                return;
            }

            // Find stale records (uuid != correctUuid)
            List<PlayerRecord> staleRecords = new ArrayList<>();
            for (PlayerRecord record : allRecords) {
                if (!record.uuid.equals(correctUuid)) {
                    staleRecords.add(record);
                }
            }

            if (staleRecords.isEmpty()) {
                Notification.info(sender, "§aPlayer {0} UUID is already correct: {1}", playerName, correctUuid);
                Notification.info(sender, "§aNo stale records found.");
                return;
            }

            // FIX 1: Multiple stale UUID detection (name collision safety)
            if (staleRecords.size() > 1) {
                try (Connection conn = DatabaseManager.instance.getConnection()) {
                    Notification.error(sender, "§c⚠ MULTIPLE STALE UUIDs DETECTED");
                    Notification.error(sender, "§cFound {0} stale UUIDs for player ''{1}'':", staleRecords.size(), playerName);
                    Notification.error(sender, "§a  Current (online): {0}", correctUuid);
                    for (PlayerRecord stale : staleRecords) {
                        int domCount = countAffected(conn, "dominion", "owner", stale.uuid);
                        Notification.error(sender, "§c  Stale: {0} (owns {1} dominions)", stale.uuid, domCount);
                    }
                    Notification.error(sender, "");
                    Notification.error(sender, "§cThis may indicate:");
                    Notification.error(sender, "§c  1. Name collision - different players used this name");
                    Notification.error(sender, "§c  2. Multiple online/offline mode switches");
                    Notification.error(sender, "");
                    Notification.error(sender, "§cTo prevent accidental data transfer:");
                    Notification.error(sender, "§c  - Manually verify which dominions belong to current player");
                    Notification.error(sender, "§c  - Check player login history");
                    Notification.error(sender, "§c  - Use manual SQL fix if needed");
                }
                throw new DominionException("Multiple stale UUIDs detected - operation aborted for safety.");
            }

            // Step 4: Show preview
            if (!confirm) {
                showPreview(sender, playerName, staleRecords, correctUuid, correctRecord != null);
                return;
            }

            // Step 5: Execute update transaction
            try (Connection conn = DatabaseManager.instance.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    int totalUpdated = 0;
                    int totalConflictDeleted = 0;  // FIX 3: Track conflict deletions
                    
                    // FIX 2: If no correct UUID row exists, INSERT it FIRST to satisfy FK constraints
                    if (correctRecord == null) {
                        String checkSql = "SELECT COUNT(*) FROM player_name WHERE uuid = ?";
                        try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                            ps.setString(1, correctUuid.toString());
                            try (ResultSet rs = ps.executeQuery()) {
                                rs.next();
                                if (rs.getInt(1) == 0) {
                                    PlayerDOO.create(correctUuid, playerName);
                                    XLogger.info("Created player_name row for correctUuid: {0}", correctUuid);
                                }
                            }
                        }
                    }
                    
                    // Process EACH stale UUID
                    for (PlayerRecord staleRecord : staleRecords) {
                        UUID staleUuid = staleRecord.uuid;
                        
                        Notification.info(sender, "§6Processing stale UUID: {0}", staleUuid);
                        
                        // CRITICAL: Update children FIRST (FK constraints)
                        totalUpdated += updateUuid(conn, "dominion", "owner", staleUuid, correctUuid);
                        totalUpdated += updateUuid(conn, "dominion", "outpost_admin_uuid", staleUuid, correctUuid);
                        totalUpdated += updateUuid(conn, "dominion", "attached_by", staleUuid, correctUuid);
                        totalUpdated += updateUuid(conn, "dominion_member", "player_uuid", staleUuid, correctUuid);
                        
                        if (tableExists(conn, "dominion_member_archive")) {
                            totalUpdated += updateUuid(conn, "dominion_member_archive", "player_uuid", staleUuid, correctUuid);
                        }
                        
                        if (tableExists(conn, "town")) {
                            totalUpdated += updateUuid(conn, "town", "founder_uuid", staleUuid, correctUuid);
                            totalUpdated += updateUuid(conn, "town", "mayor_uuid", staleUuid, correctUuid);
                        }
                        
                        if (tableExists(conn, "town_member")) {
                            int[] result = updateTownMember(conn, staleUuid, correctUuid);
                            totalUpdated += result[0];
                            totalConflictDeleted += result[1];
                        }
                        
                        if (tableExists(conn, "town_trust")) {
                            int[] result = updateTownTrust(conn, staleUuid, correctUuid);
                            totalUpdated += result[0];
                            totalConflictDeleted += result[1];
                        }
                        
                        if (tableExists(conn, "privilege_template")) {
                            totalUpdated += updateUuid(conn, "privilege_template", "creator", staleUuid, correctUuid);
                        }
                    }
                    
                    // LAST: Delete ALL stale player_name rows
                    for (PlayerRecord staleRecord : staleRecords) {
                        String sql = "DELETE FROM player_name WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setInt(1, staleRecord.id);
                            int deleted = ps.executeUpdate();
                            totalUpdated += deleted;
                            XLogger.info("Deleted stale player_name row: id={0}, uuid={1}", staleRecord.id, staleRecord.uuid);
                        }
                    }
                    
                    conn.commit();
                    
                    // Reload cache
                    CacheManager.instance.reloadCache();
                    
                    Notification.info(sender, "§a=== UUID Fix Complete ===");
                    Notification.info(sender, "§aPlayer: §e{0}", playerName);
                    Notification.info(sender, "§aStale UUIDs fixed: §c{0}", staleRecords.size());
                    for (PlayerRecord stale : staleRecords) {
                        Notification.info(sender, "§7  - {0}", stale.uuid);
                    }
                    Notification.info(sender, "§aCorrect UUID: §2{0}", correctUuid);
                    Notification.info(sender, "§aTotal records updated: §2{0}", totalUpdated);
                    if (totalConflictDeleted > 0) {
                        Notification.warn(sender, "§6PK conflict deletions: §e{0} §6(records where correct UUID already existed)", totalConflictDeleted);
                    }
                    Notification.info(sender, "");
                    Notification.info(sender, "§aCache reloaded automatically. Changes are now active.");
                    Notification.info(sender, "§aYou can now use §e/dom give§a with this player.");
                    
                    XLogger.info("UUID fixed: {0} | {1} stale UUID(s) migrated to {2} | {3} records updated | {4} conflicts deleted", 
                            playerName, staleRecords.size(), correctUuid, totalUpdated, totalConflictDeleted);
                    
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            
        } catch (Exception e) {
            Notification.error(sender, "Failed to fix UUID: {0}", e.getMessage());
            XLogger.error("UUID fix failed for {0}: {1}", playerName, e.getMessage());
            e.printStackTrace();
        }
    }

    private static void showPreview(CommandSender sender, String playerName, List<PlayerRecord> staleRecords, 
                                    UUID correctUuid, boolean hasCorrectRecord) throws SQLException {
        Notification.warn(sender, "§6=== UUID Fix Preview ===");
        Notification.warn(sender, "§6Player: §e{0}", playerName);
        Notification.warn(sender, "§6Correct UUID: §a{0}", correctUuid);
        Notification.warn(sender, "§6Stale UUID(s) found: §c{0}", staleRecords.size());
        
        for (PlayerRecord stale : staleRecords) {
            Notification.warn(sender, "§c  - {0}", stale.uuid);
        }
        
        if (hasCorrectRecord) {
            Notification.warn(sender, "§a✓ Correct UUID row exists in player_name.");
            Notification.warn(sender, "§c  Stale row(s) will be DELETED after migrating children.");
        } else {
            Notification.warn(sender, "§c⚠ No correct UUID row exists.");
            Notification.warn(sender, "§6  Will INSERT correct UUID row, then delete stale rows.");
        }
        
        Notification.warn(sender, "");
        Notification.warn(sender, "§6Records to migrate:");
        
        try (Connection conn = DatabaseManager.instance.getConnection()) {
            int totalDominion = 0, totalOutpostAdmin = 0, totalAttachedBy = 0;
            int totalMember = 0, totalTownMember = 0, totalTownTrust = 0;
            int totalTownTrustCreator = 0, totalTownFounder = 0, totalTownMayor = 0;
            
            for (PlayerRecord stale : staleRecords) {
                totalDominion += countAffected(conn, "dominion", "owner", stale.uuid);
                totalOutpostAdmin += countAffected(conn, "dominion", "outpost_admin_uuid", stale.uuid);
                totalAttachedBy += countAffected(conn, "dominion", "attached_by", stale.uuid);
                totalMember += countAffected(conn, "dominion_member", "player_uuid", stale.uuid);
                totalTownMember += countAffected(conn, "town_member", "player_uuid", stale.uuid);
                totalTownTrust += countAffected(conn, "town_trust", "player_uuid", stale.uuid);
                totalTownTrustCreator += countAffected(conn, "town_trust", "created_by", stale.uuid);
                totalTownFounder += countAffected(conn, "town", "founder_uuid", stale.uuid);
                totalTownMayor += countAffected(conn, "town", "mayor_uuid", stale.uuid);
            }
            
            Notification.warn(sender, "§6  - {0} dominion(s) owned", totalDominion);
            Notification.warn(sender, "§6  - {0} outpost admin assignment(s)", totalOutpostAdmin);
            Notification.warn(sender, "§6  - {0} dominion attachment(s)", totalAttachedBy);
            Notification.warn(sender, "§6  - {0} dominion member record(s)", totalMember);
            Notification.warn(sender, "§6  - {0} town member record(s)", totalTownMember);
            Notification.warn(sender, "§6  - {0} town trust record(s)", totalTownTrust);
            Notification.warn(sender, "§6  - {0} town trust creator record(s)", totalTownTrustCreator);
            Notification.warn(sender, "§6  - {0} town founder record(s)", totalTownFounder);
            Notification.warn(sender, "§6  - {0} town mayor record(s)", totalTownMayor);
            
            int total = totalDominion + totalOutpostAdmin + totalAttachedBy + totalMember + 
                       totalTownMember + totalTownTrust + totalTownTrustCreator + totalTownFounder + totalTownMayor;
            Notification.warn(sender, "");
            Notification.warn(sender, "§6Total: §e{0} record(s) will be updated", total);
        }
        
        Notification.warn(sender, "");
        Notification.warn(sender, "§cTo confirm, run:");
        Notification.warn(sender, "§c/dom admin fix_uuid {0} confirm", playerName);
    }

    private static int[] updateTownMember(Connection conn, UUID oldUuid, UUID newUuid) throws SQLException {
        int updated = 0;
        int deleted = 0;
        
        // Skip rows where (town_id, new_uuid) already exists (composite PK conflict)
        String sql = "UPDATE town_member SET player_uuid = ? WHERE player_uuid = ? " +
                     "AND NOT EXISTS (SELECT 1 FROM town_member tm2 WHERE tm2.town_id = town_member.town_id AND tm2.player_uuid = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, oldUuid.toString());
            ps.setString(3, newUuid.toString());
            updated = ps.executeUpdate();
        }
        
        // Delete remaining old rows (PK conflict cases)
        sql = "DELETE FROM town_member WHERE player_uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, oldUuid.toString());
            deleted = ps.executeUpdate();
            if (deleted > 0) {
                XLogger.warn("Deleted {0} conflicting town_member rows (correct UUID already existed)", deleted);
            }
        }
        
        return new int[]{updated, deleted};
    }

    private static int[] updateTownTrust(Connection conn, UUID oldUuid, UUID newUuid) throws SQLException {
        int updated = 0;
        int deleted = 0;
        
        // Update player_uuid (skip PK conflicts)
        String sql = "UPDATE town_trust SET player_uuid = ? WHERE player_uuid = ? " +
                     "AND NOT EXISTS (SELECT 1 FROM town_trust tt2 WHERE tt2.town_id = town_trust.town_id " +
                     "AND tt2.player_uuid = ? AND tt2.scope_type = town_trust.scope_type AND tt2.scope_id = town_trust.scope_id)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, oldUuid.toString());
            ps.setString(3, newUuid.toString());
            updated += ps.executeUpdate();
        }
        
        // Delete remaining old rows (PK conflict cases)
        sql = "DELETE FROM town_trust WHERE player_uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, oldUuid.toString());
            deleted = ps.executeUpdate();
            if (deleted > 0) {
                XLogger.warn("Deleted {0} conflicting town_trust rows (correct UUID already existed)", deleted);
            }
        }
        
        // Update created_by (no PK constraint)
        updated += updateUuid(conn, "town_trust", "created_by", oldUuid, newUuid);
        
        return new int[]{updated, deleted};
    }

    private static int countAffected(Connection conn, String table, String column, UUID uuid) throws SQLException {
        if (!tableExists(conn, table)) return 0;
        if (!columnExists(conn, table, column)) return 0;
        
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private static int updateUuid(Connection conn, String table, String column, UUID oldUuid, UUID newUuid) throws SQLException {
        if (!columnExists(conn, table, column)) return 0;
        
        String sql = "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, oldUuid.toString());
            return ps.executeUpdate();
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }
}
