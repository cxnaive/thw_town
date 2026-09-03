package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.databse.FIelds.Field;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldInteger;
import cn.lunadeer.dominion.utils.databse.FIelds.FieldString;
import cn.lunadeer.dominion.utils.databse.syntax.Insert;
import cn.lunadeer.dominion.utils.databse.syntax.Select;
import cn.lunadeer.dominion.utils.databse.syntax.Update;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cn.lunadeer.dominion.utils.Misc.formatString;

public class MultiServerManager {

    public static class MultiServerManagerText extends ConfigurationPart {
        public String getIdByNameError = "Server name ({0}) does not exist.";
        public String getNameByIdError = "Server ID ({0}) does not exist.";
        public String warnUpdateServerName = "There is already a server with ID {0} and name {1}, but the current server name is {2}. Updating the name to {2}.";
    }

    public static MultiServerManager instance;
    private final JavaPlugin plugin;
    private final Map<Integer, String> cachedDerverMap = new ConcurrentHashMap<>();

    public MultiServerManager(JavaPlugin plugin) {
        instance = this;
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        try {
            List<Map<String, Field<?>>> res = Select.select(
                    new FieldString("name")
            ).from("server_info").where("id = ?", Configuration.multiServer.serverId).execute();
            if (res.isEmpty()) {
                // insert
                Insert.insert().into("server_info")
                        .values(
                                new FieldInteger("id", Configuration.multiServer.serverId),
                                new FieldString("name", Configuration.multiServer.serverName)
                        ).execute();
            } else {
                // update
                String name = (String) res.get(0).get("name").getValue();
                if (!name.equals(Configuration.multiServer.serverName)) {
                    XLogger.warn(Language.multiServerManagerText.warnUpdateServerName, Configuration.multiServer.serverId, name, Configuration.multiServer.serverName);
                    Update.update("server_info")
                            .set(new FieldString("name", Configuration.multiServer.serverName))
                            .where("id = ?", Configuration.multiServer.serverId)
                            .execute();
                }
            }
            cachedDerverMap.put(Configuration.multiServer.serverId, Configuration.multiServer.serverName);
        } catch (Exception e) {
            XLogger.error(e);
        }

    }

    public void connectToServer(@NotNull Player player, @NotNull String serverName) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(serverName);
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
    }

    public String getServerName(int serverId) throws Exception {
        if (cachedDerverMap.containsKey(serverId)) {
            return cachedDerverMap.get(serverId);
        }
        List<Map<String, Field<?>>> res = Select.select(
                new FieldString("name")
        ).from("server_info").where("id = ?", serverId).execute();
        if (res.isEmpty()) {
            throw new Exception(formatString(Language.multiServerManagerText.getNameByIdError, serverId));
        }
        cachedDerverMap.put(serverId, (String) res.get(0).get("name").getValue());
        return cachedDerverMap.get(serverId);
    }

    /** Format an internal backend name for player-facing cross-server UI. */
    public static @NotNull String formatServerLabel(int serverId, @Nullable String internalName) {
        String displayName = internalName == null ? null
                : Configuration.multiServer.serverDisplayNames.getOrDefault(internalName, internalName);
        return displayName == null || displayName.isBlank()
                ? "#" + serverId
                : displayName + " (#" + serverId + ")";
    }

    /**
     * Load all known backend server names in one query for cross-server UI
     * pages. The per-id accessor remains available for command paths.
     */
    public Map<Integer, String> getServerNames() throws Exception {
        List<Map<String, Field<?>>> res = Select.select(
                new FieldInteger("id"),
                new FieldString("name")
        ).from("server_info").execute();
        Map<Integer, String> names = new HashMap<>();
        for (Map<String, Field<?>> row : res) {
            Object idValue = row.get("id").getValue();
            Object nameValue = row.get("name").getValue();
            if (idValue instanceof Integer id && nameValue instanceof String name) {
                names.put(id, name);
            }
        }
        cachedDerverMap.putAll(names);
        return names;
    }

    public Integer getServerId(@NotNull String serverName) throws Exception {
        if (cachedDerverMap.containsValue(serverName)) {
            return cachedDerverMap.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(serverName))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new Exception(formatString(Language.multiServerManagerText.getIdByNameError, serverName)));
        }
        List<Map<String, Field<?>>> res = Select.select(
                new FieldInteger("id")
        ).from("server_info").where("name = ?", serverName).execute();
        if (res.isEmpty()) {
            throw new Exception(formatString(Language.multiServerManagerText.getIdByNameError, serverName));
        }
        Integer id = (Integer) res.get(0).get("id").getValue();
        cachedDerverMap.put(id, serverName);
        return id;
    }
}
