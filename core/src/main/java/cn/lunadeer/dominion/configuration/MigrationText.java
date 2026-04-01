package cn.lunadeer.dominion.configuration;

import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;

public class MigrationText extends ConfigurationPart {
    public String folderNotFound = "Migration folder not found: {0}";
    public String fileMissing = "Missing required file: {0}";
    public String importing = "Migrating database from {0} with server_id {1}...";
    public String phaseProgress = "[Phase {0}] {1}: {2}/{3} ({4}%)";
    public String phaseDone = "[Phase {0}] {1} completed, {2} records processed.";
    public String worldUidRemap = "World UID remapped: {0} -> {1}";
    public String worldNotFound = "World {0} ({1}) not found on this server, skipping UID remap.";
    public String migrateSuccess = "Database migration completed successfully. {0} players, {1} dominions, {2} groups, {3} members migrated.";
    public String migrateFailed = "Database migration failed: {0}";
    public String playerIdMap = "Player ID map: {0} entries";
    public String dominionIdMap = "Dominion ID map: {0} entries";
    public String groupIdMap = "Group ID map: {0} entries";
}
