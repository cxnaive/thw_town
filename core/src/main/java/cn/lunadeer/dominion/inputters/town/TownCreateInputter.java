package cn.lunadeer.dominion.inputters.town;

import cn.lunadeer.dominion.commands.TownCommand;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.town.TownInfo;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Create-town inputter (plan §13.3 UI-4): the main-menu replacement for
 * the retired personal-dominion create button. Landless players are
 * guided to select two points first (the founding outpost requirement).
 */
public class TownCreateInputter {

    public static class TownCreateInputterText extends ConfigurationPart {
        public String button = "CREATE TOWN";
        public String hint = "Enter the new town name.";
        public String confirmHint = "Type 'confirm' to create the town with the listed land attached.";
        public String description = "Create a town: your land attaches, or a founding outpost is created from your selection.";
    }

    public static void createOn(CommandSender sender) {
        if (!(sender instanceof Player player)) return;
        new InputterRunner(sender, Language.townCreateInputterText.hint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
                    try {
                        TownCommand.createTownInteractive(player, input.trim());
                        TownDOO town = TownManager.ownTownOf(player);
                        if (town != null) {
                            cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                                    () -> TownInfo.show(sender, town.getName(), "1"));
                        }
                    } catch (Exception e) {
                        Notification.error(sender, e);
                    }
                });
            }
        };
    }

    public static FunctionalButton createTuiButtonOn(CommandSender sender) {
        return new FunctionalButton(Language.townCreateInputterText.button) {
            @Override
            public void function() {
                createOn(sender);
            }
        };
    }
}
