package cn.lunadeer.dominion.configuration;

import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.api.dtos.flag.Flag;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.commands.*;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.handler.DominionProviderHandler;
import cn.lunadeer.dominion.handler.GroupProviderHandler;
import cn.lunadeer.dominion.handler.MemberProviderHandler;
import cn.lunadeer.dominion.handler.SelectPointEventsHandler;
import cn.lunadeer.dominion.inputters.*;

import cn.lunadeer.dominion.managers.DatabaseTables;
import cn.lunadeer.dominion.managers.MultiServerManager;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.TeleportManager;
import cn.lunadeer.dominion.misc.Asserts;
import cn.lunadeer.dominion.misc.Converts;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.XLogger;
import cn.lunadeer.dominion.utils.command.InvalidArgumentException;
import cn.lunadeer.dominion.utils.command.NoPermissionException;
import cn.lunadeer.dominion.utils.configuration.*;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Headers({
        "Language file for Dominion plugin",
        "If you want to help translate this file, please refer to:",
        "https://dominion.lunadeer.cn/en/notes/doc/owner/config-ref/languages",
        "for more instructions.",
        "",
        "Most of the text support color codes,",
        "you can use §0-§9 for colors, §l for bold, §o for italic, §n for underline, §m for strikethrough, and §k for magic.",
        "Also support '&' as an alternative for '§'.",
})
public class Language extends ConfigurationFile {

    // languages file name list here will be saved to plugin data folder
    @HandleManually
    public enum LanguageCode {
        en_us,
        zh_cn,
        jp_jp,
        zh_tw,
    }

    public static void loadLanguageFiles(CommandSender sender, JavaPlugin plugin, String code) {
        try {
            // save default language files to the languages folder
            File languagesFolder = new File(Dominion.instance.getDataFolder(), "languages");
            File cuiFolder = new File(languagesFolder, "cui");
            File tuiFolder = new File(languagesFolder, "tui");
            for (LanguageCode languageCode : LanguageCode.values()) {
                updateLanguageFiles(plugin, languageCode.name(), false);
            }
            // ConfigurationManager uses Bukkit's forgiving loader, which
            // turns malformed YAML into an empty configuration and then saves
            // defaults over it. Validate the active files strictly first so
            // an operator typo can never destroy a customized locale.
            validateLanguageFile(new File(languagesFolder, code + ".yml"));
            validateLanguageFile(new File(cuiFolder, code + ".yml"));
            validateLanguageFile(new File(tuiFolder, code + ".yml"));
            Notification.info(sender != null ? sender : Dominion.instance.getServer().getConsoleSender(), Language.configurationText.loadingLanguage, code);
            ConfigurationManager.load(Language.class, new File(languagesFolder, code + ".yml"));
            ConfigurationManager.load(ChestUserInterface.class, new File(cuiFolder, code + ".yml"));
            ConfigurationManager.load(TextUserInterface.class, new File(tuiFolder, code + ".yml"));
            Notification.info(sender != null ? sender : Dominion.instance.getServer().getConsoleSender(), Language.configurationText.loadLanguageSuccess, code);
        } catch (Exception e) {
            Notification.error(sender != null ? sender : Dominion.instance.getServer().getConsoleSender(), Language.configurationText.loadLanguageFail, code, e.getMessage());
        }
    }

    public static boolean updateLanguageFiles(JavaPlugin plugin, String code, boolean overwrite) {
        File languagesFolder = new File(plugin.getDataFolder(), "languages");
        if (!languagesFolder.exists()) {
            languagesFolder.mkdir();
        }
        File cuiFolder = new File(languagesFolder, "cui");
        if (!cuiFolder.exists()) {
            cuiFolder.mkdir();
        }
        File tuiFolder = new File(languagesFolder, "tui");
        if (!tuiFolder.exists()) {
            tuiFolder.mkdir();
        }
        boolean main = installOrMergeLanguageFile(plugin, "languages/" + code + ".yml",
                new File(languagesFolder, code + ".yml"), overwrite);
        boolean cui = installOrMergeLanguageFile(plugin, "languages/cui/" + code + ".yml",
                new File(cuiFolder, code + ".yml"), overwrite);
        boolean tui = installOrMergeLanguageFile(plugin, "languages/tui/" + code + ".yml",
                new File(tuiFolder, code + ".yml"), overwrite);
        return main && cui && tui;
    }

    /**
     * Preserve every operator customization while copying newly introduced
     * keys from the matching bundled locale.  ConfigurationManager also
     * fills missing fields, but its reflection defaults are English; merging
     * the locale resource first keeps upgraded Chinese/Japanese installations
     * localized without asking the operator to delete language files.
     */
    private static boolean installOrMergeLanguageFile(JavaPlugin plugin, String resourcePath,
                                                      File target, boolean overwrite) {
        try {
            if (overwrite) {
                plugin.saveResource(resourcePath, true);
                return true;
            }
            if (!target.exists()) {
                plugin.saveResource(resourcePath, false);
                return true;
            }
            try (InputStream stream = plugin.getResource(resourcePath)) {
                if (stream == null) {
                    XLogger.warn("Bundled language resource not found: {0}", resourcePath);
                    return false;
                }
                YamlConfiguration bundled = new YamlConfiguration();
                bundled.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                YamlConfiguration installed = loadLanguageFileStrict(target);
                boolean changed = false;
                for (String key : bundled.getKeys(true)) {
                    if (bundled.isConfigurationSection(key) || installed.contains(key)) continue;
                    installed.set(key, bundled.get(key));
                    changed = true;
                }
                if (changed) {
                    installed.options().width(250);
                    saveLanguageFileAtomically(installed, target);
                }
            }
            return true;
        } catch (Exception e) {
            XLogger.warn("Failed to install/update language resource {0}: {1}",
                    resourcePath, e.getMessage());
            XLogger.warn("See https://dominion.lunadeer.cn/en/notes/doc/owner/config-ref/languages , If you want to help us to add this language.");
            return false;
        }
    }

    private static void validateLanguageFile(File file) throws Exception {
        if (file.exists()) loadLanguageFileStrict(file);
    }

    private static YamlConfiguration loadLanguageFileStrict(File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.load(file);
        return yaml;
    }

    /** Write through a sibling temp file so the original survives any failure. */
    private static void saveLanguageFileAtomically(YamlConfiguration yaml, File target) throws Exception {
        Path targetPath = target.toPath();
        Path temp = Files.createTempFile(targetPath.getParent(), target.getName(), ".tmp");
        try {
            yaml.save(temp.toFile());
            try {
                Files.move(temp, targetPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static Dominion.DominionText dominionText = new Dominion.DominionText();
    public static AbstractUI.ConsoleText consoleText = new AbstractUI.ConsoleText();
    public static AbstractUI.UiCommandsDescription uiCommandsDescription = new AbstractUI.UiCommandsDescription();

    public static MultiServerManager.MultiServerManagerText multiServerManagerText = new MultiServerManager.MultiServerManagerText();
    public static TownManager.TownManagerText townManagerText = new TownManager.TownManagerText();
    public static TownCommand.TownCommandText townCommandText = new TownCommand.TownCommandText();
    public static TownRoleText townRoleText = new TownRoleText();

    public static Asserts.AssertsText assertsText = new Asserts.AssertsText();
    public static Converts.ConvertsText convertsText = new Converts.ConvertsText();
    public static Others.OthersText othersText = new Others.OthersText();

    public static VaultConnect.VaultConnectText vaultConnectText = new VaultConnect.VaultConnectText();

    // Event Handler
    public static DominionProviderHandler.DominionProviderHandlerText dominionProviderHandlerText = new DominionProviderHandler.DominionProviderHandlerText();
    public static MemberProviderHandler.MemberProviderHandlerText memberProviderHandlerText = new MemberProviderHandler.MemberProviderHandlerText();
    public static GroupProviderHandler.GroupProviderHandlerText groupProviderHandlerText = new GroupProviderHandler.GroupProviderHandlerText();
    public static SelectPointEventsHandler.SelectPointEventsHandlerText selectPointEventsHandlerText = new SelectPointEventsHandler.SelectPointEventsHandlerText();

    // Inputter
    public static CreateDominionInputter.CreateDominionInputterText createDominionInputterText = new CreateDominionInputter.CreateDominionInputterText();
    public static CreateGroupInputter.CreateGroupInputterText createGroupInputterText = new CreateGroupInputter.CreateGroupInputterText();
    public static RenameDominionInputter.RenameDominionInputterText renameDominionInputterText = new RenameDominionInputter.RenameDominionInputterText();
    public static cn.lunadeer.dominion.inputters.town.TownDisbandConfirmInputter.TownDisbandConfirmInputterText townDisbandConfirmInputterText = new cn.lunadeer.dominion.inputters.town.TownDisbandConfirmInputter.TownDisbandConfirmInputterText();
    public static cn.lunadeer.dominion.inputters.town.TownOutpostAdminInputter.TownOutpostAdminInputterText townOutpostAdminInputterText = new cn.lunadeer.dominion.inputters.town.TownOutpostAdminInputter.TownOutpostAdminInputterText();
    public static cn.lunadeer.dominion.inputters.town.TownOutpostCreateInputter.TownOutpostCreateInputterText townOutpostCreateInputterText = new cn.lunadeer.dominion.inputters.town.TownOutpostCreateInputter.TownOutpostCreateInputterText();
    public static cn.lunadeer.dominion.inputters.town.TownCreateInputter.TownCreateInputterText townCreateInputterText = new cn.lunadeer.dominion.inputters.town.TownCreateInputter.TownCreateInputterText();
    public static EditMessageInputter.EditMessageInputterText editMessageInputterText = new EditMessageInputter.EditMessageInputterText();
    public static CreateTemplateInputter.CreateTemplateInputterText createTemplateInputterText = new CreateTemplateInputter.CreateTemplateInputterText();
    public static RenameGroupInputter.RenameGroupInputterText renameGroupInputterText = new RenameGroupInputter.RenameGroupInputterText();
    public static ResizeDominionInputter.ResizeDominionInputterText resizeDominionInputterText = new ResizeDominionInputter.ResizeDominionInputterText();
    public static SearchPlayerInputter.SearchPlayerInputterText searchPlayerInputterText = new SearchPlayerInputter.SearchPlayerInputterText();
    public static SetMapColorInputter.SetMapColorInputterText setMapColorInputterText = new SetMapColorInputter.SetMapColorInputterText();
    public static RenameTemplateInputter.RenameTemplateInputterText renameTemplateInputterText = new RenameTemplateInputter.RenameTemplateInputterText();

    // Commands
    public static AdministratorCommand.AdministratorCommandText administratorCommandText = new AdministratorCommand.AdministratorCommandText();
    public static MigrationCommand.MigrationCommandText migrationCommandText = new MigrationCommand.MigrationCommandText();
    public static TemplateCommand.TemplateCommandText templateCommandText = new TemplateCommand.TemplateCommandText();
    public static GroupTitleCommand.GroupTitleCommandText groupTitleCommandText = new GroupTitleCommand.GroupTitleCommandText();
    public static CopyCommand.CopyCommandText copyCommandText = new CopyCommand.CopyCommandText();
    public static DominionOperateCommand.DominionOperateCommandText dominionOperateCommandText = new DominionOperateCommand.DominionOperateCommandText();
    public static DominionCreateCommand.DominionCreateCommandText dominionCreateCommandText = new DominionCreateCommand.DominionCreateCommandText();
    public static DominionFlagCommand.DominionFlagCommandText dominionFlagCommandText = new DominionFlagCommand.DominionFlagCommandText();
    public static GroupCommand.GroupCommandText groupCommandText = new GroupCommand.GroupCommandText();
    public static MemberCommand.MemberCommandText memberCommandText = new MemberCommand.MemberCommandText();

    public static Configuration.ConfigurationText configurationText = new Configuration.ConfigurationText();

    public static Limitation.LimitationText limitationText = new Limitation.LimitationText();

    public static DatabaseTables.DatabaseManagerText databaseManagerText = new DatabaseTables.DatabaseManagerText();

    public static MigrationText migrationText = new MigrationText();

    public static TeleportManager.TeleportManagerText teleportManagerText = new TeleportManager.TeleportManagerText();

    public static CommandExceptionText commandExceptionText = new CommandExceptionText();

    public static class CommandExceptionText extends ConfigurationPart {
        public String noPermission = "You do not have permission {0} to do this.";
        public String invalidArguments = "Invalid arguments, usage e.g. {0}.";
    }

    public static InputterText inputterText = new InputterText();

    public static class InputterText extends ConfigurationPart {
        public String onlyPlayer = "TUI inputter can only be used by a player.";
        public String cancel = " [Send 'C' to cancel the inputter.]";
        public String inputterCancelled = "Inputter cancelled.";
    }

    public static class TownRoleText extends ConfigurationPart {
        // TownRole display names
        public String mayor = "Mayor";
        public String assistant = "Assistant";
        public String resident = "Resident";
        public String outsider = "Outsider";
        // MemberSource display names
        public String founder = "Founder";
        public String joined = "Joined";
        public String merged = "Merged";
    }

    @PreProcess
    public void loadFlagsText() {
        for (Flag flag : Flags.getAllFlags()) {
            if (getYaml().contains(flag.getDisplayNameKey())) {
                flag.setDisplayName(getYaml().getString(flag.getDisplayNameKey()));
            } else {
                getYaml().set(flag.getDisplayNameKey(), flag.getDisplayName());
            }
            if (getYaml().contains(flag.getDescriptionKey())) {
                flag.setDescription(getYaml().getString(flag.getDescriptionKey()));
            } else {
                getYaml().set(flag.getDescriptionKey(), flag.getDescription());
            }
        }
    }

    @PostProcess
    public static void setOtherStaticText() {
        // cn.lunadeer.dominion.utils.command
        InvalidArgumentException.MSG = commandExceptionText.invalidArguments;
        NoPermissionException.MSG = commandExceptionText.noPermission;

        InputterRunner.ONLY_PLAYER = inputterText.onlyPlayer;
        InputterRunner.CANCEL = inputterText.cancel;
        InputterRunner.INPUTTER_CANCELLED = inputterText.inputterCancelled;
    }

}
