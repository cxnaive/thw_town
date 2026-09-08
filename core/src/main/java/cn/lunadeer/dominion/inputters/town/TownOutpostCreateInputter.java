package cn.lunadeer.dominion.inputters.town;

import cn.lunadeer.dominion.api.dtos.CuboidDTO;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.misc.Others;
import cn.lunadeer.dominion.uis.town.TownInfo;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.VaultConnect.VaultConnect;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import static cn.lunadeer.dominion.misc.Converts.getSelectedPoints;

/** Create a town-native outpost from the player's current two-point selection. */
public final class TownOutpostCreateInputter {

    public static class TownOutpostCreateInputterText extends ConfigurationPart {
        public String hint = "Enter the new outpost name. The two currently selected points will be used.";
    }

    public static void createOn(@NotNull Player player, @NotNull String townName) {
        final World world;
        final CuboidDTO cuboid;
        try {
            Location[] points = getSelectedPoints(player);
            world = points[0].getWorld();
            if (world == null) {
                throw new cn.lunadeer.dominion.misc.DominionException(
                        Language.selectPointEventsHandlerText.selectionToolHint, Configuration.selectTool);
            }
            cn.lunadeer.dominion.misc.Asserts.assertCreationAllowed(player, world);
            cuboid = TownManager.normalizeTownOutpostCuboid(new CuboidDTO(points[0], points[1]));
        } catch (Exception e) {
            Notification.error(player, e);
            return;
        }

        Notification.info(player, Language.selectPointEventsHandlerText.selectionToolHint,
                Configuration.selectTool);
        double price = Others.bypassLimit(player) ? 0
                : TownManager.townOutpostPrice(CuboidDTO.ZERO, cuboid);
        Notification.info(player, Language.selectPointEventsHandlerText.townOutpostPrice,
                price, VaultConnect.instance.currencyNamePluralOrEmpty());

        new InputterRunner(player, Language.townOutpostCreateInputterText.hint) {
            @Override
            public void run(String input) {
                String name = input.trim();
                Scheduler.runTaskAsync(() -> {
                    try {
                        TownDOO town = TownManager.requireTown(townName);
                        TownManager.createRemoteOutpost(player, town, name, world, cuboid);
                        Scheduler.runTask(() -> TownInfo.show(player, town.getName(), "1"));
                    } catch (Exception e) {
                        Notification.error(player, e);
                    }
                });
            }
        };
    }

    public static FunctionalButton createTuiButtonOn(@NotNull Player player,
                                                      @NotNull String townName,
                                                      @NotNull String label) {
        return new FunctionalButton(label) {
            @Override
            public void function() {
                createOn(player, townName);
            }
        };
    }

    private TownOutpostCreateInputter() {
    }
}
