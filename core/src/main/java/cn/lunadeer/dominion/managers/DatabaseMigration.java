package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.Backup;
import cn.lunadeer.dominion.utils.databse.FIelds.*;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

public class DatabaseMigration {

    public static void migrateDatabase(CommandSender sender, String folderName, int newServerId) {
        Scheduler.runTaskAsync(() -> {
            try {
                doMigrate(sender, folderName, newServerId);
            } catch (Exception e) {
                XLogger.error(e);
                Notification.error(sender, Language.migrationText.migrateFailed, e.getMessage());
            }
        });
    }

    private static void doMigrate(CommandSender sender, String folderName, int newServerId) throws Exception {
        File folder = new File(Dominion.instance.getDataFolder(), folderName);
        if (!folder.exists() || !folder.isDirectory()) {
            Notification.error(sender, Language.migrationText.folderNotFound, folder.getAbsolutePath());
            return;
        }

        // Validate all required files
        String[] requiredFiles = {"player_name.csv", "privilege_template.csv", "dominion.csv", "dominion_group.csv", "dominion_member.csv", "world_uid_mapping.yml"};
        for (String fileName : requiredFiles) {
            if (!new File(folder, fileName).exists()) {
                Notification.error(sender, Language.migrationText.fileMissing, fileName);
                return;
            }
        }

        Notification.info(sender, Language.migrationText.importing, folderName, String.valueOf(newServerId));

        // Build world UID remap from source export
        Map<String, String> worldUidRemap = buildWorldUidRemap(sender, folder);

        // Parse all CSV files
        List<Map<String, Field<?>>> playerRows = Backup.parseCsvToRows(new File(folder, "player_name.csv"));
        List<Map<String, Field<?>>> templateRows = Backup.parseCsvToRows(new File(folder, "privilege_template.csv"));
        List<Map<String, Field<?>>> dominionRows = Backup.parseCsvToRows(new File(folder, "dominion.csv"));
        List<Map<String, Field<?>>> groupRows = Backup.parseCsvToRows(new File(folder, "dominion_group.csv"));
        List<Map<String, Field<?>>> memberRows = Backup.parseCsvToRows(new File(folder, "dominion_member.csv"));

        // Phase 1: Migrate players with UUID deduplication
        Map<Integer, Integer> playerIdMap = migratePlayers(sender, playerRows);
        Notification.info(sender, Language.migrationText.playerIdMap, String.valueOf(playerIdMap.size()));

        // Phase 2: Migrate privilege templates
        Map<Integer, Integer> templateIdMap = migrateTemplates(sender, templateRows);

        // Phase 3: Migrate dominions
        Map<Integer, Integer> dominionIdMap = migrateDominions(sender, dominionRows, newServerId, worldUidRemap);
        Notification.info(sender, Language.migrationText.dominionIdMap, String.valueOf(dominionIdMap.size()));

        // Phase 4: Migrate dominion groups
        Map<Integer, Integer> groupIdMap = migrateGroups(sender, groupRows, dominionIdMap);
        Notification.info(sender, Language.migrationText.groupIdMap, String.valueOf(groupIdMap.size()));

        // Phase 5: Migrate dominion members
        int memberCount = migrateMembers(sender, memberRows, dominionIdMap, groupIdMap);

        // Reload cache
        Scheduler.runTask(() -> CacheManager.instance.reloadCache());

        Notification.info(sender, Language.migrationText.migrateSuccess,
                String.valueOf(playerIdMap.size()),
                String.valueOf(dominionIdMap.size()),
                String.valueOf(groupIdMap.size()),
                String.valueOf(memberCount));
    }

    private static Map<String, String> buildWorldUidRemap(CommandSender sender, File folder) {
        Map<String, String> remap = new HashMap<>();
        File mappingFile = new File(folder, "world_uid_mapping.yml");
        YamlConfiguration sourceMapping = YamlConfiguration.loadConfiguration(mappingFile);
        Map<String, String> currentWorlds = new HashMap<>();
        for (World w : Dominion.instance.getServer().getWorlds()) {
            currentWorlds.put(w.getName(), w.getUID().toString());
        }
        for (String worldName : sourceMapping.getKeys(false)) {
            String oldUid = sourceMapping.getString(worldName);
            if (currentWorlds.containsKey(worldName)) {
                String newUid = currentWorlds.get(worldName);
                if (!oldUid.equals(newUid)) {
                    remap.put(oldUid, newUid);
                    Notification.info(sender, Language.migrationText.worldUidRemap, oldUid, newUid);
                }
            } else {
                Notification.warn(sender, Language.migrationText.worldNotFound, worldName, oldUid);
            }
        }
        return remap;
    }

    private static int getIntValue(Map<String, Field<?>> row, String column) {
        Field<?> f = row.get(column);
        if (f == null || f.getValue() == null) return 0;
        return (Integer) f.getValue();
    }

    private static String getStringValue(Map<String, Field<?>> row, String column) {
        Field<?> f = row.get(column);
        if (f == null || f.getValue() == null) return "";
        return (String) f.getValue();
    }

    private static boolean getBooleanValue(Map<String, Field<?>> row, String column) {
        Field<?> f = row.get(column);
        if (f == null || f.getValue() == null) return false;
        return (Boolean) f.getValue();
    }

    private static float getFloatValue(Map<String, Field<?>> row, String column) {
        Field<?> f = row.get(column);
        if (f == null || f.getValue() == null) return 0f;
        return (Float) f.getValue();
    }

    // Phase 1: Migrate players, deduplicate by UUID
    private static Map<Integer, Integer> migratePlayers(CommandSender sender, List<Map<String, Field<?>>> rows) throws SQLException {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int count = 0;
        for (Map<String, Field<?>> row : rows) {
            int oldId = getIntValue(row, "id");

            String uuid = getStringValue(row, "uuid");

            // Check if player already exists by UUID
            List<Map<String, Field<?>>> existing = Select.select(new Field<?>[]{new FieldInteger("id")})
                    .from("player_name")
                    .where("uuid = ?", uuid)
                    .execute();
            if (!existing.isEmpty()) {
                int existingId = (Integer) existing.get(0).get("id").getValue();
                idMap.put(oldId, existingId);
            } else {
                // Insert new player (without id, let DB auto-generate)
                FieldString uuidField = new FieldString("uuid", uuid);
                FieldString nameField = new FieldString("last_known_name", getStringValue(row, "last_known_name"));
                FieldTimestamp joinAtField = new FieldTimestamp("last_join_at",
                        row.get("last_join_at") != null ? (java.sql.Timestamp) row.get("last_join_at").getValue() : new java.sql.Timestamp(0));
                FieldString uiPrefField = new FieldString("ui_preference", getStringValue(row, "ui_preference"));
                FieldInteger groupTitleField = new FieldInteger("using_group_title_id", -1);
                FieldString skinUrlField = new FieldString("skin_url", getStringValue(row, "skin_url"));

                Map<String, Field<?>> result = Insert.insert()
                        .into("player_name")
                        .values(uuidField, nameField, joinAtField, uiPrefField, groupTitleField, skinUrlField)
                        .returning(new Field<?>[]{new FieldInteger("id")})
                        .execute();
                if (!result.isEmpty()) {
                    int newId = (Integer) result.get("id").getValue();
                    idMap.put(oldId, newId);
                }
            }
            count++;
            if (count % 100 == 0) {
                logProgress(sender, 1, "player_name", count, rows.size());
            }
        }
        logProgress(sender, 1, "player_name", count, rows.size());
        return idMap;
    }

    // Phase 2: Migrate privilege templates
    private static Map<Integer, Integer> migrateTemplates(CommandSender sender, List<Map<String, Field<?>>> rows) throws SQLException {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int count = 0;
        for (Map<String, Field<?>> row : rows) {
            int oldId = getIntValue(row, "id");
            if (oldId == -1) continue;

            String creatorUuid = getStringValue(row, "creator");
            ensurePlayerExists(creatorUuid);

            List<Field<?>> insertFields = new ArrayList<>();
            insertFields.add(new FieldString("creator", creatorUuid));
            insertFields.add(new FieldString("name", getStringValue(row, "name")));
            insertFields.add(new FieldBoolean("admin", getBooleanValue(row, "admin")));

            // Add all flag columns from the source row (skip id)
            for (Map.Entry<String, Field<?>> entry : row.entrySet()) {
                String colName = entry.getKey();
                if (colName.equals("id") || colName.equals("creator") || colName.equals("name") || colName.equals("admin")) continue;
                if (entry.getValue() instanceof FieldBoolean) {
                    insertFields.add(new FieldBoolean(colName, getBooleanValue(row, colName)));
                }
            }

            Map<String, Field<?>> result = Insert.insert()
                    .into("privilege_template")
                    .values(insertFields.toArray(new Field[0]))
                    .returning(new Field<?>[]{new FieldInteger("id")})
                    .execute();
            if (!result.isEmpty()) {
                int newId = (Integer) result.get("id").getValue();
                idMap.put(oldId, newId);
            }
            count++;
            if (count % 100 == 0) {
                logProgress(sender, 2, "privilege_template", count, rows.size());
            }
        }
        logProgress(sender, 2, "privilege_template", count, rows.size());
        return idMap;
    }

    // Phase 3: Migrate dominions (most complex - parent_dom_id self-reference)
    private static Map<Integer, Integer> migrateDominions(CommandSender sender, List<Map<String, Field<?>>> rows, int newServerId, Map<String, String> worldUidRemap) throws SQLException {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int count = 0;
        for (Map<String, Field<?>> row : rows) {
            int oldId = getIntValue(row, "id");
            if (oldId == -1) continue; // skip root dominion

            int oldParentDomId = getIntValue(row, "parent_dom_id");
            int newParentDomId;
            if (oldParentDomId == -1) {
                newParentDomId = -1;
            } else {
                newParentDomId = idMap.getOrDefault(oldParentDomId, -1);
                if (newParentDomId == -1 && oldParentDomId != -1) {
                    XLogger.warn("Parent dominion {0} not found in map for dominion {1}, skipping", oldParentDomId, oldId);
                    continue;
                }
            }

            // Remap world_uid
            String oldWorldUid = getStringValue(row, "world_uid");
            String newWorldUid = worldUidRemap.getOrDefault(oldWorldUid, oldWorldUid);

            // Ensure owner exists in player_name (FK constraint)
            String ownerUuid = getStringValue(row, "owner");
            ensurePlayerExists(ownerUuid);

            List<Field<?>> insertFields = new ArrayList<>();
            insertFields.add(new FieldString("owner", ownerUuid));
            insertFields.add(new FieldString("name", getStringValue(row, "name")));
            insertFields.add(new FieldString("world_uid", newWorldUid));
            insertFields.add(new FieldInteger("x1", getIntValue(row, "x1")));
            insertFields.add(new FieldInteger("y1", getIntValue(row, "y1")));
            insertFields.add(new FieldInteger("z1", getIntValue(row, "z1")));
            insertFields.add(new FieldInteger("x2", getIntValue(row, "x2")));
            insertFields.add(new FieldInteger("y2", getIntValue(row, "y2")));
            insertFields.add(new FieldInteger("z2", getIntValue(row, "z2")));
            insertFields.add(new FieldInteger("parent_dom_id", newParentDomId));
            insertFields.add(new FieldString("join_message", getStringValue(row, "join_message")));
            insertFields.add(new FieldString("leave_message", getStringValue(row, "leave_message")));
            insertFields.add(new FieldString("tp_location", getStringValue(row, "tp_location")));
            insertFields.add(new FieldString("color", getStringValue(row, "color")));
            insertFields.add(new FieldInteger("server_id", newServerId));

            // Add all flag columns from the source row (skip known columns)
            Set<String> skipColumns = Set.of("id", "owner", "name", "world_uid",
                    "x1", "y1", "z1", "x2", "y2", "z2",
                    "parent_dom_id", "join_message", "leave_message",
                    "tp_location", "color", "server_id", "world");
            for (Map.Entry<String, Field<?>> entry : row.entrySet()) {
                String colName = entry.getKey();
                if (skipColumns.contains(colName)) continue;
                if (entry.getValue() instanceof FieldBoolean) {
                    insertFields.add(new FieldBoolean(colName, getBooleanValue(row, colName)));
                }
            }

            Map<String, Field<?>> result = Insert.insert()
                    .into("dominion")
                    .values(insertFields.toArray(new Field[0]))
                    .returning(new Field<?>[]{new FieldInteger("id")})
                    .execute();
            if (!result.isEmpty()) {
                int newId = (Integer) result.get("id").getValue();
                idMap.put(oldId, newId);
            } else {
                XLogger.warn("Failed to insert dominion {0} (owner={1}, name={2}), result is empty",
                        oldId, ownerUuid, getStringValue(row, "name"));
            }
            count++;
            if (count % 100 == 0) {
                logProgress(sender, 3, "dominion", count, rows.size());
            }
        }
        logProgress(sender, 3, "dominion", count, rows.size());
        return idMap;
    }

    // Phase 4: Migrate dominion groups
    private static Map<Integer, Integer> migrateGroups(CommandSender sender, List<Map<String, Field<?>>> rows, Map<Integer, Integer> dominionIdMap) throws SQLException {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int count = 0;
        for (Map<String, Field<?>> row : rows) {
            int oldId = getIntValue(row, "id");
            int oldDomId = getIntValue(row, "dom_id");
            Integer newDomId = dominionIdMap.get(oldDomId);
            if (newDomId == null) {
                XLogger.warn("Dominion {0} not found in map for group {1}, skipping", oldDomId, oldId);
                continue;
            }

            List<Field<?>> insertFields = new ArrayList<>();
            insertFields.add(new FieldInteger("dom_id", newDomId));
            insertFields.add(new FieldString("name", getStringValue(row, "name")));
            insertFields.add(new FieldString("name_colored", getStringValue(row, "name_colored")));
            insertFields.add(new FieldBoolean("admin", getBooleanValue(row, "admin")));

            // Add all flag columns (skip known columns)
            Set<String> skipColumns = Set.of("id", "dom_id", "name", "name_colored", "admin");
            for (Map.Entry<String, Field<?>> entry : row.entrySet()) {
                String colName = entry.getKey();
                if (skipColumns.contains(colName)) continue;
                if (entry.getValue() instanceof FieldBoolean) {
                    insertFields.add(new FieldBoolean(colName, getBooleanValue(row, colName)));
                }
            }

            Map<String, Field<?>> result = Insert.insert()
                    .into("dominion_group")
                    .values(insertFields.toArray(new Field[0]))
                    .returning(new Field<?>[]{new FieldInteger("id")})
                    .execute();
            if (!result.isEmpty()) {
                int newGroupId = (Integer) result.get("id").getValue();
                idMap.put(oldId, newGroupId);
            }
            count++;
            if (count % 100 == 0) {
                logProgress(sender, 4, "dominion_group", count, rows.size());
            }
        }
        logProgress(sender, 4, "dominion_group", count, rows.size());
        return idMap;
    }

    // Phase 5: Migrate dominion members
    private static int migrateMembers(CommandSender sender, List<Map<String, Field<?>>> rows, Map<Integer, Integer> dominionIdMap, Map<Integer, Integer> groupIdMap) throws SQLException {
        int count = 0;
        for (Map<String, Field<?>> row : rows) {
            int oldDomId = getIntValue(row, "dom_id");
            Integer newDomId = dominionIdMap.get(oldDomId);
            if (newDomId == null) {
                XLogger.warn("Dominion {0} not found in map for member, skipping", oldDomId);
                continue;
            }

            int oldGroupId = getIntValue(row, "group_id");
            int newGroupId;
            if (oldGroupId == -1) {
                newGroupId = -1;
            } else {
                newGroupId = groupIdMap.getOrDefault(oldGroupId, -1);
            }

            List<Field<?>> insertFields = new ArrayList<>();
            insertFields.add(new FieldString("player_uuid", getStringValue(row, "player_uuid")));
            insertFields.add(new FieldInteger("dom_id", newDomId));
            insertFields.add(new FieldBoolean("admin", getBooleanValue(row, "admin")));
            insertFields.add(new FieldInteger("group_id", newGroupId));

            // Add all flag columns (skip known columns)
            Set<String> skipColumns = Set.of("id", "player_uuid", "dom_id", "admin", "group_id");
            for (Map.Entry<String, Field<?>> entry : row.entrySet()) {
                String colName = entry.getKey();
                if (skipColumns.contains(colName)) continue;
                if (entry.getValue() instanceof FieldBoolean) {
                    insertFields.add(new FieldBoolean(colName, getBooleanValue(row, colName)));
                }
            }

            Insert.insert()
                    .into("dominion_member")
                    .values(insertFields.toArray(new Field[0]))
                    .execute();
            count++;
            if (count % 100 == 0) {
                logProgress(sender, 5, "dominion_member", count, rows.size());
            }
        }
        logProgress(sender, 5, "dominion_member", count, rows.size());
        return count;
    }

    private static void logProgress(CommandSender sender, int phase, String tableName, int current, int total) {
        String progress = String.format("%.1f", current * 100.0 / Math.max(total, 1));
        XLogger.info("[Phase " + phase + "] " + tableName + ": " + current + "/" + total + " (" + progress + "%)");
    }

    /**
     * Ensure a player exists in player_name table by UUID.
     * If not found, insert a minimal record to satisfy FK constraints.
     */
    private static void ensurePlayerExists(String uuid) throws SQLException {
        if (uuid == null || uuid.isEmpty()) return;
        List<Map<String, Field<?>>> existing = Select.select(new Field<?>[]{new FieldInteger("id")})
                .from("player_name")
                .where("uuid = ?", uuid)
                .execute();
        if (existing.isEmpty()) {
            XLogger.warn("Player {0} not found in source player_name, creating placeholder record to satisfy FK constraint.", uuid);
            Insert.insert()
                    .into("player_name")
                    .values(new FieldString("uuid", uuid),
                            new FieldString("last_known_name", ""),
                            new FieldInteger("using_group_title_id", -1))
                    .execute();
        }
    }
}
