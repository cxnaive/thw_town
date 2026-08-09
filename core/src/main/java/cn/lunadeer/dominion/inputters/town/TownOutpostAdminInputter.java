package cn.lunadeer.dominion.inputters.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.dominion.DominionManage;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.command.CommandSender;

/**
 * Appoint/clear the outpost admin of a town outpost (plan §2.4, §13.3).
 * Enter a player name to appoint, or "clear" to remove.
 */
public class TownOutpostAdminInputter {

    public static class TownOutpostAdminInputterText extends ConfigurationPart {
        public String button = "OUTPOST ADMIN";
        public String hint = "Enter the player name to appoint, or 'clear' to remove.";
        public String description = "Appoint or clear the outpost admin.";
    }

    public static void createOn(CommandSender sender, String dominionName) {
        new InputterRunner(sender, Language.townOutpostAdminInputterText.hint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> runAsync(sender, input));
            }

            private void runAsync(CommandSender sender, String input) {
                try {
                    if (!(sender instanceof org.bukkit.entity.Player player)) return;
                    DominionDOO outpost = (DominionDOO) CacheManager.instance.getDominion(dominionName);
                    if (outpost == null) {
                        Notification.error(sender, Language.townCommandText.dominionNotExist, dominionName);
                        return;
                    }
                    if (input.trim().equalsIgnoreCase("clear")) {
                        TownManager.setOutpostAdmin(player, outpost, null);
                    } else {
                        cn.lunadeer.dominion.api.dtos.PlayerDTO target =
                                CacheManager.instance.getPlayer(input.trim());
                        if (target == null) {
                            Notification.error(sender, Language.townCommandText.playerNotFound, input);
                            return;
                        }
                        TownManager.setOutpostAdmin(player, outpost, target.getUuid());
                    }
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                            () -> DominionManage.show(sender, dominionName, "1"));
                } catch (Exception e) {
                    Notification.error(sender, e);
                }
            }
        };
    }

    public static FunctionalButton createTuiButtonOn(CommandSender sender, String dominionName) {
        return new FunctionalButton(Language.townOutpostAdminInputterText.button) {
            @Override
            public void function() {
                createOn(sender, dominionName);
            }
        };
    }
}
