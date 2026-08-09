package cn.lunadeer.dominion.inputters.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.DominionDOO;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Disband confirmation inputter: shows the exact same warning a
 * `/town disband` preview prints (outpost count, contributor list,
 * policy-aware wording) and only proceeds on the literal "confirm".
 */
public class TownDisbandConfirmInputter {

    public static class TownDisbandConfirmInputterText extends ConfigurationPart {
        public String button = "DISBAND";
        public String hint = "Type 'confirm' to disband the town.";
    }

    public static void createOn(CommandSender sender, String townName) {
        // the preview query hits the database — compute it async, then
        // warn and register the inputter on the main thread
        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
            try {
                Player player = (Player) sender;
                TownDOO town = TownManager.requireTown(townName);
                TownManager.assertManager(player, town);
                List<DominionDOO> outposts = TownManager.outpostsOf(town.getId());
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                        () -> prompt(sender, townName, outposts));
            } catch (Exception e) {
                Notification.error(sender, e);
            }
        });
    }

    private static void prompt(CommandSender sender, String townName, List<DominionDOO> outposts) {
        try {
            Player player = (Player) sender;
            TownDOO town = TownManager.requireTown(townName);
            List<String> contributors = new ArrayList<>();
            for (DominionDOO outpost : outposts) {
                if (outpost.getAttachedBy() == null) continue;
                String contributor = CacheManager.instance.getPlayerName(outpost.getOwner());
                if (!contributors.contains(contributor)) {
                    contributors.add(contributor);
                }
            }
            boolean dissolveAll = !"return-contributed".equals(Configuration.town.disbandPolicy);
            if (dissolveAll) {
                Notification.warn(sender, Language.townCommandText.disbandConfirm,
                        town.getName(), outposts.size(), outpostNames(outposts));
                if (!contributors.isEmpty()) {
                    Notification.warn(sender, Language.townCommandText.disbandConfirmContributors,
                            String.join(", ", contributors));
                }
            } else {
                List<DominionDOO> townNative = new ArrayList<>();
                for (DominionDOO outpost : outposts) {
                    if (outpost.getAttachedBy() == null) {
                        townNative.add(outpost);
                    }
                }
                Notification.warn(sender, Language.townCommandText.disbandConfirmReturn,
                        town.getName(), townNative.size(), outpostNames(townNative));
                if (!contributors.isEmpty()) {
                    Notification.warn(sender, Language.townCommandText.disbandConfirmContributorsReturn,
                            String.join(", ", contributors));
                }
            }

            new InputterRunner(sender, Language.townDisbandConfirmInputterText.hint) {
                @Override
                public void run(String input) {
                    if (!input.trim().equalsIgnoreCase("confirm")) {
                        Notification.warn(sender, Language.townDisbandConfirmInputterText.hint);
                        return;
                    }
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> {
                        try {
                            // roles may have changed since the prompt
                            // opened — re-assert before the destructive op
                            TownManager.assertManager(sender, TownManager.requireTown(townName));
                            TownManager.disbandTown(sender, TownManager.requireTown(townName));
                        } catch (Exception e) {
                            Notification.error(sender, e);
                            return;
                        }
                        cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                                () -> cn.lunadeer.dominion.uis.MainMenu.show(sender, "1"));
                    });
                }
            };
        } catch (Exception e) {
            Notification.error(sender, e);
        }
    }

    public static FunctionalButton createTuiButtonOn(CommandSender sender, String townName) {
        return new FunctionalButton(Language.townDisbandConfirmInputterText.button) {
            @Override
            public void function() {
                createOn(sender, townName);
            }
        };
    }

    private static String outpostNames(List<DominionDOO> outposts) {
        List<String> names = new ArrayList<>();
        for (DominionDOO outpost : outposts) {
            names.add(outpost.getName());
        }
        return String.join(", ", names);
    }
}
