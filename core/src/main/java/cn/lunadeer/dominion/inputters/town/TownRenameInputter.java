package cn.lunadeer.dominion.inputters.town;

import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.town.TownInfo;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Chat inputter for changing a town's shared name. */
public class TownRenameInputter {

    public static class TownRenameInputterText extends ConfigurationPart {
        public String button = "RENAME TOWN";
        public String hint = "Enter a new town name (spaces are not allowed).";
        public String description = "Rename this town (mayor only).";
    }

    public static void createOn(CommandSender sender, String townName) {
        createOn(sender, townName, null);
    }

    public static void createOn(CommandSender sender, Integer townId) {
        createOn(sender, null, townId);
    }

    private static void createOn(CommandSender sender, String townName, Integer townId) {
        new InputterRunner(sender, Language.townRenameInputterText.hint) {
            @Override
            public void run(String input) {
                Scheduler.runTaskAsync(() -> {
                    try {
                        Player player = (Player) sender;
                        TownDOO town = townId == null
                                ? TownManager.requireTown(townName)
                                : TownManager.requireTown(townId);
                        TownDOO renamed = TownManager.renameTown(player, town, input);
                        // Inventory/TUI rendering touches Bukkit player state;
                        // Folia requires it to run on the player's entity
                        // scheduler after the database mutation completes.
                        Scheduler.runEntityTask(() -> TownInfo.show(player, renamed.getName(), "1"), player);
                    } catch (Exception e) {
                        Notification.error(sender, e);
                    }
                });
            }
        };
    }

    public static FunctionalButton createTuiButtonOn(CommandSender sender, String townName) {
        return createTuiButtonOn(sender, null, townName);
    }

    public static FunctionalButton createTuiButtonOn(CommandSender sender, Integer townId) {
        return createTuiButtonOn(sender, townId, null);
    }

    private static FunctionalButton createTuiButtonOn(CommandSender sender, Integer townId, String townName) {
        return new FunctionalButton(Language.townRenameInputterText.button) {
            @Override
            public void function() {
                if (townId == null) {
                    createOn(sender, townName);
                } else {
                    createOn(sender, townId);
                }
            }
        };
    }
}
