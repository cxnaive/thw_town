package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Update;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maintains the world_map table and self-heals world UID drift.
 * <p>
 * Dominion rows reference worlds by Bukkit's World.getUID() (stored in
 * uid.dat inside the world folder), not by world name. Copying a world
 * folder preserves the UID, but REGENERATING or REPLACING a world under
 * the same name produces a new UID and orphans every dominion pointing at
 * the old one.
 * <p>
 * The world_map table is the stable anchor: the world NAME survives
 * regeneration, the UID does not. Reconciliation runs on plugin enable
 * (before caches load), on WorldLoadEvent for late-loading worlds
 * (Multiverse etc.), and after a database import: each server registers
 * its loaded worlds (new worlds are harmless inserts) and heals drift by
 * rewriting every dominion row of THIS server still referencing the old
 * UID. Claims follow the world NAME — including intentional same-name map
 * resets. Each backend heals only its own rows (server_id scoping), so no
 * single server needs to see every world. Every heal is logged with
 * old/new UID and row count.
 * <p>
 * The table has no primary key because the ORM only supports single-column
 * primaries; uniqueness of (server_id, name) is upheld by the fact that
 * only the owning server ever writes its own server_id rows, always via
 * this select-then-write path.
 */
public class WorldMapManager {

    /**
     * Return the stable world names known by every backend server, keyed by
     * server id and world UID. This is used by cross-server views where a
     * remote Bukkit World instance is not available locally.
     */
    public static Map<Integer, Map<UUID, String>> getWorldNames() throws Exception {
        Map<Integer, Map<UUID, String>> result = new HashMap<>();
        List<Map<String, Field<?>>> rows = Select.select(
                new FieldInteger("server_id"),
                new FieldString("name"),
                new FieldString("uid")
        ).from("world_map").execute();
        for (Map<String, Field<?>> row : rows) {
            Object serverValue = row.get("server_id").getValue();
            Object nameValue = row.get("name").getValue();
            Object uidValue = row.get("uid").getValue();
            if (!(serverValue instanceof Integer serverId)
                    || !(nameValue instanceof String name)
                    || !(uidValue instanceof String uid)) {
                continue;
            }
            try {
                result.computeIfAbsent(serverId, ignored -> new HashMap<>())
                        .put(UUID.fromString(uid), name);
            } catch (IllegalArgumentException ignored) {
                // An invalid legacy UID cannot match a Dominion UUID; keep
                // the UI's UUID fallback instead of failing the whole view.
            }
        }
        return result;
    }

    /**
     * Tables carrying a (world_uid, server_id) pair that must be healed on
     * UID drift. Phase 2: add "town_claim" here.
     */
    private static final List<String> WORLD_UID_TABLES = List.of("dominion");

    /**
     * Reconcile every currently loaded world. Runs on plugin enable
     * (before caches load) and after a database import.
     */
    public static void reconcile() {
        for (World world : Dominion.instance.getServer().getWorlds()) {
            reconcileWorld(world);
        }
    }

    /**
     * Register one world in world_map and heal UID drift for this server's
     * rows. Idempotent; also driven by WorldLoadEvent for worlds loaded
     * after plugin enable.
     */
    public static void reconcileWorld(World world) {
        int serverId = Configuration.multiServer.serverId;
        String name = world.getName();
        String actualUid = world.getUID().toString();
        try {
            String storedUid = storedUid(serverId, name);
            if (storedUid == null) {
                Insert.insert().into("world_map")
                        .values(new FieldInteger("server_id", serverId),
                                new FieldString("name", name),
                                new FieldString("uid", actualUid))
                        .execute();
                return;
            }
            if (actualUid.equals(storedUid)) {
                return;
            }
            int healed = heal(serverId, storedUid, actualUid);
            Update.update("world_map")
                    .set(new FieldString("uid", actualUid))
                    .where("server_id = ? AND name = ?", serverId, name)
                    .execute();
            XLogger.warn("World \"{0}\" was regenerated or replaced (UID {1} -> {2}); remapped {3} dominion(s) of this server to the new UID.",
                    name, storedUid, actualUid, healed);
        } catch (Exception e) {
            // A failed world must not abort startup/import; other worlds still reconcile.
            XLogger.error("Failed to reconcile world_map for world " + name);
            XLogger.error(e);
        }
    }

    /**
     * Bridge a LEGACY world_uid_mapping.yml (pre-4.7.8 backups — the only
     * anchor format back then) into world_map: for every world hosted on
     * THIS server whose live UID differs from the mapped old UID, (re)set
     * the anchor row to the OLD uid. The reconcile pass that follows then
     * sees the drift and heals through the normal path. Without this,
     * restoring such a backup after a world was regenerated would silently
     * orphan those dominions: anchors written at first boot only know
     * current UIDs.
     */
    public static void bridgeLegacyMapping(File mappingFile) {
        if (!mappingFile.exists()) {
            return;
        }
        int serverId = Configuration.multiServer.serverId;
        YamlConfiguration mapping = YamlConfiguration.loadConfiguration(mappingFile);
        for (String worldName : mapping.getKeys(false)) {
            String oldUid = mapping.getString(worldName);
            World world = Dominion.instance.getServer().getWorld(worldName);
            if (oldUid == null || world == null) {
                continue; // world not hosted here; its own server bridges/heals it
            }
            if (world.getUID().toString().equals(oldUid)) {
                continue; // no drift for this world
            }
            try {
                String storedUid = storedUid(serverId, worldName);
                if (storedUid == null) {
                    Insert.insert().into("world_map")
                            .values(new FieldInteger("server_id", serverId),
                                    new FieldString("name", worldName),
                                    new FieldString("uid", oldUid))
                            .execute();
                } else if (!storedUid.equals(oldUid)) {
                    Update.update("world_map")
                            .set(new FieldString("uid", oldUid))
                            .where("server_id = ? AND name = ?", serverId, worldName)
                            .execute();
                }
                XLogger.info("Legacy world mapping bridged for world \"{0}\" (anchor UID {1}).", worldName, oldUid);
            } catch (Exception e) {
                XLogger.error("Failed to bridge legacy world mapping for world " + worldName);
                XLogger.error(e);
            }
        }
    }

    /**
     * Rewrite this server's rows in WORLD_UID_TABLES from oldUid to newUid.
     */
    private static int heal(int serverId, String oldUid, String newUid) throws Exception {
        int healed = 0;
        for (String table : WORLD_UID_TABLES) {
            healed += Update.update(table)
                    .set(new FieldString("world_uid", newUid))
                    .where("world_uid = ? AND server_id = ?", oldUid, serverId)
                    .execute();
        }
        return healed;
    }

    private static String storedUid(int serverId, String worldName) throws Exception {
        List<Map<String, Field<?>>> res = Select.select(new FieldString("uid"))
                .from("world_map")
                .where("server_id = ? AND name = ?", serverId, worldName)
                .execute();
        if (res.isEmpty()) {
            return null;
        }
        return (String) res.get(0).get("uid").getValue();
    }
}
