package cn.lunadeer.dominion.configuration;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.api.dtos.PlayerDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flag;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.managers.DatabaseTables;
import cn.lunadeer.dominion.utils.MessageDisplay;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.configuration.*;
import cn.lunadeer.dominion.utils.databse.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static cn.lunadeer.dominion.configuration.Language.loadLanguageFiles;

@Headers({
        "This is the configuration file of Dominion.",
        "For more information, please refer to the documentation.",
        "https://dominion.lunadeer.cn/en/notes/doc/owner/config-ref/overview/",
        "",
        "If you want to control player's privilege, please refer to the limitations configuration.",
})
public class Configuration extends ConfigurationFile {

    public static class ConfigurationText extends ConfigurationPart {
        public String loadingLanguage = "Loading language {0}...";
        public String loadLanguageFail = "Failed to load language {0} reason: {1}, using default en_us.";
        public String loadLanguageSuccess = "Successfully loaded language {0}.";

        public String loadingFlag = "Loading flag configuration...";
        public String loadFlagSuccess = "Successfully loaded flag configuration.";

        public String loadingLimitations = "Loading limitation configuration...";
        public String savingDefaultLimitation = "Because no limitation file found, saving default limitation file.";
        public String saveLimitationFail = "Failed to save limitation file: {0}";
        public String loadingLimitation = "Loading limitation file: {0}...";
        public String loadLimitationFail = "Failed to load limitation file: {0} reason: {1}";
        public String loadLimitations = "Successfully loaded {0} limitations: {1}.";

        public String loadConfiguration = "Successfully loaded configuration.";
        public String debugEnabled = "Debug mode enabled.";
        public String prepareDatabase = "Preparing database...";
        public String databaseConnected = "Database connected successfully.";

        public String multiServerSqlite = "Database with type sqlite is not supported in multi-server mode, disabled multi-server mode.";
        public String serverIdInvalid = "Server id must be positive integer (> 0), disabled multi-server mode.";
    }

    @HandleManually
    public static void loadFlagConfiguration() throws IOException {
        XLogger.info(Language.configurationText.loadingFlag);
        File yamlFile = new File(Dominion.instance.getDataFolder(), "flags.yml");
        if (!yamlFile.exists()) {
            boolean re = yamlFile.createNewFile();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(yamlFile);
        for (Flag flag : Flags.getAllFlags()) {
            if (yaml.contains(flag.getConfigurationDefaultKey())) {
                flag.setDefaultValue(yaml.getBoolean(flag.getConfigurationDefaultKey()));
            } else {
                yaml.set(flag.getConfigurationDefaultKey(), flag.getDefaultValue());
            }
            if (yaml.contains(flag.getConfigurationEnableKey())) {
                flag.setEnable(yaml.getBoolean(flag.getConfigurationEnableKey()));
            } else {
                yaml.set(flag.getConfigurationEnableKey(), flag.getEnable());
            }
            if (yaml.contains(flag.getConfigurationMaterialKey())) {
                flag.setMaterial(yaml.getString(flag.getConfigurationMaterialKey()));
            } else {
                yaml.set(flag.getConfigurationMaterialKey(), flag.getMaterial().name());
            }
            yaml.setInlineComments(flag.getConfigurationNameKey(), Collections.singletonList(flag.getDisplayName() + "-" + flag.getDescription()));
        }
        yaml.save(yamlFile);
        XLogger.info(Language.configurationText.loadFlagSuccess);
    }

    @Comments("Do not modify this value.")
    public static int version = 3;  // <<<<<< When you change the configuration, you should increment this value.

    @Comments("The settings of the database.")
    public static Database database = new Database();

    public static class Database extends ConfigurationPart {
        @Comments("Supported types: sqlite, mysql, pgsql")
        public String type = "sqlite";

        @Comments("The host of the database.")
        public String host = "localhost";

        @Comments("The port of the database.")
        public String port = "3306";

        @Comments("The database name.")
        public String database = "dominion";

        @Comments("The username of the database.")
        public String username = "dominion";

        @Comments("The password of the database.")
        public String password = "dominion";

        @Comments({"The connection pool size of the database.",})
        public int connectionPoolSize = 10;
    }

    @Comments({
            "The settings of the multi server.",
            "If you have multiple servers proxied by BungeeCord, you can configure and enable this.",
            "Player can manage/teleport across multi-servers.",
            "Database with type sqlite is not supported in multi-server mode.",
            "For migration of existing data, please refer to the documentation.",
            "https://dominion.lunadeer.cn/notes/doc/owner/other/multi-server/"
    })
    public static MultiServer multiServer = new MultiServer();

    public static class MultiServer extends ConfigurationPart {
        @Comments("Enable multi server mode.")
        public boolean enable = false;
        @Comments({
                "The name of this server.",
                "This should be the same as configured in BC (Velocity)."
        })
        public String serverName = "server";
        @Comments({
                "The id of this server, must be unique among all servers.",
                "Must be positive integer. > 0",
                "DO NOT CHANGE THIS AFTER THERE ARE DATA IN THE DATABASE."
        })
        public int serverId = 1;
    }

    @Comments("Language of the plugin, see others in the plugins/Dominion/languages folder.")
    public static String language = "en_us";

    @Comments({
            "The land system rules of the town era.",
    })
    public static LandSystem landSystem = new LandSystem();

    public static class LandSystem extends ConfigurationPart {
        @Comments({
                "Whether players can create new personal dominions.",
                "Under the town model this stays false: new land only enters",
                "the world through towns (founding/remote outposts)."
        })
        public boolean allowNewPersonalDominion = false;
        @Comments({
                "Whether players can create new sub dominions.",
                "Frozen under the town model; existing sub dominions keep working."
        })
        public boolean allowNewSubDominion = false;
        @Comments("Whether townless legacy dominions can be resized.")
        public boolean legacyAllowResize = false;
        @Comments("Whether townless legacy dominions can be deleted by their owner.")
        public boolean legacyAllowDelete = true;
    }

    @Comments({
            "The town system.",
            "All existing dominions are outposts; a dominion with town_id set",
            "belongs to a town and is governed by the town cascade."
    })
    public static Town town = new Town();

    public static class Town extends ConfigurationPart {
        @Comments({
                "Max number of towns a player may formally belong to.",
                "Counts TownMember only (COUNT(DISTINCT town_id)); TownTrust,",
                "invites and visitors are unaffected. -1 for unlimited."
        })
        public int maxMemberships = 1;
        @Comments({
                "When a player's dominions attach to a town, merge their old",
                "dominion members into town members while capacity allows.",
                "false: ALL old members become outpost-scoped TownTrust instead."
        })
        public boolean mergeMembersOnAttach = true;
        @Comments("Whether players owning legacy land may join a town (their land attaches).")
        public boolean allowJoinWithLegacyLand = true;
        @Comments("Cost of the founding outpost charged from the founder's wallet.")
        public double foundingCost = 0;
        @Comments({
                "Edge length of the auto-created founding outpost — the town's",
                "first plot, a cube centered on the founder (e.g. 32 =",
                "32x32x32). foundingCost is the flat price of exactly this",
                "plot (0 = free starter land). Later expansion uses normal",
                "town-outpost resize rules."
        })
        public int foundingSize = 32;
        @Comments({
                "Whether towns may create new remote outposts.",
                "false: towns can only expand existing outposts (resize)."
        })
        public boolean outpostAllowRemote = true;
        @Comments({
                "What happens to outposts when a town disbands:",
                "dissolve-all: every outpost is dissolved with the town;",
                "return-contributed: contributed legacy land returns to its",
                "contributor, only contributor-less outposts dissolve."
        })
        public String disbandPolicy = "dissolve-all";
        @Comments("Enable the TownTrust system.")
        public boolean trustEnabled = true;
    }

    @Comments({
            "Radius of the auto create dominion. -1 to disable.",
            "MUST stay -1 in the town era: personal dominion creation is banned",
            "(see land-system.allow-new-personal-dominion)."
    })
    public static int autoCreateRadius = -1;

    @Comments("Prevent player from creating dominion around the spawn point.")
    public static int serverSpawnProtectionRadius = 10;

    @Comments("Tool used to select position for creating dominion.")
    public static String selectTool = "ARROW";

    @Comments("Tool used to show information of clicked dominion.")
    public static String infoTool = "STRING";

    @Comments({
            "The settings of the player default using UI. (For bedrock player, will always be CUI.)",
            "If set to CUI or TUI, player can not change their UI type.",
            "BY_PLAYER: Decide by player themselves.",
            "CUI: Chest GUI.",
            "TUI: Text GUI."
    })
    public static String defaultUiType = PlayerDTO.UI_TYPE.BY_PLAYER.name();

    @Comments("The settings of the plugin message.")
    public static PluginMessage pluginMessage = new PluginMessage();

    public static class PluginMessage extends ConfigurationPart {
        @Comments({
                "The default message when player enter/leave dominion.",
                "Variables: {OWNER} - owner of the dominion, {DOM} - name of the dominion."
        })
        public String defaultEnterMessage = "&3{OWNER}: Welcome to {DOM}!";
        public String defaultLeaveMessage = "&3{OWNER}: Leaving {DOM}...";
        @Comments({
                "Where to show the message.",
                "Supported types: BOSS_BAR, ACTION_BAR, TITLE, SUBTITLE, CHAT"
        })
        public String noPermissionDisplayPlace = "ACTION_BAR";
        public String enterLeaveDisplayPlace = "ACTION_BAR";
    }

    @Comments("Weather the player can migrate residence data to dominion.")
    public static boolean residenceMigration = false;

    @Comments("Weather the player have dominion.admin permission can bypass the dominion limitation.")
    public static boolean adminBypass = true;

    @Comments({
            "The settings of the group title.",
            "Player can use there group name as title in tab list."
    })
    public static GroupTitle groupTitle = new GroupTitle();

    public static class GroupTitle extends ConfigurationPart {
        public boolean enable = false;
        public String prefix = "[";
        public String suffix = "]";
    }

    @Comments("The settings of the external link.")
    public static ExternalLinks externalLinks = new ExternalLinks();

    public static class ExternalLinks extends ConfigurationPart {
        public String commandHelp = "";
        public String documentation = "https://dominion.lunadeer.cn/notes/doc/player/";
    }

    @Comments("Player with these permission nodes won't be affected by dominion's fly limitation.")
    public static List<String> flyPermissionNodes = List.of("essentials.fly", "cmi.command.fly", "domfly.use");

    @Comments("Check for updates by internet.")
    public static boolean checkUpdate = true;

    @Comments("Debug mode, if report bugs turn this on.")
    public static boolean debug = false;

    @Comments("Performance recorder, don't open this unless you are debugging.")
    public static boolean timer = false;

    @PostProcess
    public static void checkConfigurationParams() {
        if (database.type.equalsIgnoreCase("sqlite") && multiServer.enable) {
            XLogger.error(Language.configurationText.multiServerSqlite);
            multiServer.enable = false;
        }

        if (multiServer.serverId <= 0) {
            XLogger.error(Language.configurationText.serverIdInvalid);
            multiServer.enable = false;
        }

        if (autoCreateRadius != -1) {
            // town era: auto-create is a removed feature (personal
            // creation is banned); any legacy value carried over from a
            // pre-5.0 config is clamped to the disabled sentinel
            XLogger.warn("auto-create-radius is retired in the town era; clamping {0} to -1.", autoCreateRadius);
            autoCreateRadius = -1;
        }

        if (town.maxMemberships < 0 && town.maxMemberships != -1) {
            XLogger.warn("Invalid town max-memberships: {0}", town.maxMemberships);
            town.maxMemberships = 1;
        }
        if (town.maxMemberships == 0) {
            // 0 would deadlock the server (no one can ever create or join
            // a town); treat it as a misconfiguration of the default
            XLogger.warn("town max-memberships of 0 makes towns unusable; resetting to 1.");
            town.maxMemberships = 1;
        }
        if (town.foundingSize <= 0) {
            XLogger.warn("Invalid town founding-size: {0}", town.foundingSize);
            town.foundingSize = 32;
        }
        if (town.foundingCost < 0) {
            XLogger.warn("Invalid town founding-cost: {0}", town.foundingCost);
            town.foundingCost = 0;
        }
        if (!town.disbandPolicy.equals("dissolve-all") && !town.disbandPolicy.equals("return-contributed")) {
            XLogger.warn("Invalid town disband-policy: {0}", town.disbandPolicy);
            town.disbandPolicy = "dissolve-all";
        }

        if (Material.matchMaterial(selectTool) == null) {
            XLogger.warn("Invalid select tool: {0}", selectTool);
            selectTool = "ARROW";
        }

        if (Material.matchMaterial(infoTool) == null) {
            XLogger.warn("Invalid info tool: {0}", infoTool);
            infoTool = "STRING";
        }

        try {
            MessageDisplay.Place.valueOf(pluginMessage.noPermissionDisplayPlace.toUpperCase());
        } catch (IllegalArgumentException e) {
            XLogger.warn("Invalid no permission display place: {0}", pluginMessage.noPermissionDisplayPlace);
            pluginMessage.noPermissionDisplayPlace = "ACTION_BAR";
        }

        try {
            MessageDisplay.Place.valueOf(pluginMessage.enterLeaveDisplayPlace);
        } catch (IllegalArgumentException e) {
            XLogger.warn("Invalid enter leave display place: {0}", pluginMessage.enterLeaveDisplayPlace);
            pluginMessage.enterLeaveDisplayPlace = "ACTION_BAR";
        }

        try {
            PlayerDTO.UI_TYPE.valueOf(defaultUiType.toUpperCase());
        } catch (IllegalArgumentException e) {
            XLogger.warn("Invalid default UI type: {0}", defaultUiType);
            defaultUiType = PlayerDTO.UI_TYPE.BY_PLAYER.name();
        }
    }

    @HandleManually
    public static Map<String, Limitation> limitations = new HashMap<>();

    @PostProcess
    public static void loadLimitations() {
        XLogger.info(Language.configurationText.loadingLimitations);
        File folder = new File(Dominion.instance.getDataFolder(), "limitations");
        if (!folder.exists()) {
            boolean re = folder.mkdirs();
        }
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        if (files.length == 0) {
            try {
                XLogger.info(Language.configurationText.savingDefaultLimitation);
                ConfigurationManager.saveDefault(Limitation.class, new File(folder, "default.yml"));
                limitations.put("default", new Limitation());
            } catch (Exception e) {
                XLogger.warn(Language.configurationText.saveLimitationFail, e.getMessage());
            }
            return;
        }
        for (File file : files) {
            try {
                XLogger.info(Language.configurationText.loadingLimitation, file.getName());
                ConfigurationFile limitationFile = ConfigurationManager.load(Limitation.class, file, "version");
                Limitation limitation = (Limitation) limitationFile;
                limitations.put(file.getName().replace(".yml", ""), limitation);
            } catch (Exception e) {
                XLogger.warn(Language.configurationText.loadLimitationFail, file.getName(), e.getMessage());
            }
        }
        if (!limitations.containsKey("default")) {  // guarantee the default limitation
            try {
                ConfigurationManager.saveDefault(Limitation.class, new File(folder, "default.yml"));
                limitations.put("default", new Limitation());
            } catch (Exception e) {
                XLogger.warn(Language.configurationText.saveLimitationFail, e.getMessage());
            }
        }
        XLogger.info(Language.configurationText.loadLimitations, limitations.size(), String.join(", ", limitations.keySet()));
        // Register permissions for limitations
        for (Map.Entry<String, Limitation> entry : limitations.entrySet()) {
            String permission = "dominion.limitation." + entry.getKey();
            if (Bukkit.getPluginManager().getPermission(permission) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(permission));
            }
            String permissionGroup = "group." + entry.getKey();
            if (Bukkit.getPluginManager().getPermission(permissionGroup) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(permissionGroup));
            }
        }
    }

    @PostProcess
    public void setDebug() {
        XLogger.setDebug(debug);
        if (debug) {
            XLogger.warn(Language.configurationText.debugEnabled);
        }
    }

    /**
     * Gets the limitation for a player based on their permissions.
     * If the player has multiple limitations, the one with the lowest priority is returned.
     *
     * @param player the player whose limitation is to be retrieved, or null to get the default limitation
     * @return the limitation for the player, or the default limitation if the player is null or has no specific limitations
     */
    public static @NotNull Limitation getPlayerLimitation(@Nullable Player player) {
        if (player == null) {
            return limitations.get("default");
        }
        List<Limitation> playerLimitations = new ArrayList<>();
        for (String group : limitations.keySet()) {
            if (group.equals("default")) {
                continue;
            }
            if (player.hasPermission("group." + group) || player.hasPermission("dominion.limitation." + group)) {
                playerLimitations.add(limitations.get(group));
            }
        }
        if (playerLimitations.isEmpty()) {
            return limitations.get("default");
        } else {
            playerLimitations.sort(Comparator.comparingInt(o -> o.priority));
            return playerLimitations.get(playerLimitations.size() - 1);
        }
    }

    private static void handleLegacyConfiguration() {
        File dataFolder = Dominion.instance.getDataFolder();
        File legacyGroupsFolder = new File(dataFolder, "groups");
        if (!legacyGroupsFolder.exists()) {
            return;
        }
        File legacyFolder = new File(dataFolder, "legacy");
        if (!legacyFolder.exists()) {
            legacyFolder.mkdirs();
        }
        moveFilesToFolder(legacyGroupsFolder, new File(legacyFolder, "groups"));
        new File(dataFolder, "config.yml").renameTo(new File(legacyFolder, "config.yml"));
        moveFilesToFolder(new File(dataFolder, "languages"), new File(legacyFolder, "languages"));
    }

    private static void moveFilesToFolder(File sourceFolder, File targetFolder) {
        if (!targetFolder.exists()) {
            targetFolder.mkdirs();
        }
        for (File file : Objects.requireNonNull(sourceFolder.listFiles())) {
            file.renameTo(new File(targetFolder, file.getName()));
        }
        sourceFolder.delete();
    }

    public static void loadConfigurationAndDatabase(CommandSender sender) throws Exception {
        handleLegacyConfiguration();
        // configuration
        ConfigurationManager.load(Configuration.class, new File(Dominion.instance.getDataFolder(), "config.yml"), "version");
        Notification.info(sender != null ? sender : Dominion.instance.getServer().getConsoleSender()
                , Language.configurationText.loadConfiguration);
        // language
        loadLanguageFiles(sender, Dominion.instance, Configuration.language);
        // flag
        loadFlagConfiguration();
        // world-wide
        WorldWide.load(sender != null ? sender : Dominion.instance.getServer().getConsoleSender(), Dominion.instance);
        // database
        Notification.info(sender != null ? sender : Dominion.instance.getServer().getConsoleSender()
                , Language.configurationText.prepareDatabase);
        if (DatabaseManager.instance == null) {
            new DatabaseManager(Dominion.instance,
                    Configuration.database.type,
                    Configuration.database.host,
                    Configuration.database.port,
                    Configuration.database.database,
                    Configuration.database.username,
                    Configuration.database.password
            );
        } else {
            DatabaseManager.instance.set(
                    Configuration.database.type,
                    Configuration.database.host,
                    Configuration.database.port,
                    Configuration.database.database,
                    Configuration.database.username,
                    Configuration.database.password,
                    Configuration.database.connectionPoolSize
            );
        }
        DatabaseManager.instance.reconnect();
        Notification.info(sender != null ? sender : Dominion.instance.getServer().getConsoleSender()
                , Language.configurationText.databaseConnected);
        DatabaseTables.migrate();
    }
}
