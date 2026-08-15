package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownLevelManager;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestListView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.scui.configuration.ListViewConfiguration;
import cn.lunadeer.dominion.utils.stui.ListView;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/** Town-wide progression task and contribution page. */
public class TownLevelTasks extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        TownLevelTasks ui = new TownLevelTasks();
        Scheduler.runTaskAsync(() -> {
            try {
                TownDOO town = TownManager.requireTown(townName);
                ui.prefetched = TownLevelManager.snapshot(town);
            } catch (Exception e) {
                runForSender(sender, () -> Notification.error(sender, e));
                return;
            }
            runForSender(sender, () -> ui.displayByPreference(sender, townName, pageStr));
        });
    }

    private static void runForSender(@NotNull CommandSender sender, @NotNull Runnable task) {
        if (sender instanceof Player player) {
            Scheduler.runEntityTask(task, player);
        } else {
            Scheduler.runTask(task);
        }
    }

    private TownLevelManager.ProgressSnapshot prefetched;

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townLevelTasksTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    public static class TownLevelTasksTuiText extends ConfigurationPart {
        public String title = "Level task of {0}";
        public String button = "LEVEL TASK";
        public String levelLine = "§7Current level: §b{0}§7/§b{1}";
        public String openLevelLine = "§7Member progression currently open through: §b{0}";
        public String capacityLine = "§7Outpost limit: §b{0} total / {1} per world";
        public String taskLine = "§7Target level: §b{0} §7- required item: §e{1} x {2}";
        public String progressLine = "§7Shared progress: §a{0}§7/§a{1} §7(remaining {2})";
        public String submitButton = "SUBMIT ITEMS";
        public String submitDescription = "Submit matching items from your inventory to this shared task.";
        public String openMaxLine = "§aThis town has reached the highest level currently open to members.";
        public String missingLine = "§cNo shared task is configured for target level {0}.";
        public String outsiderLine = "§7Only town members may submit items.";
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);
            TownLevelManager.ProgressSnapshot task = requireSnapshot(town);
            boolean member = CacheManager.instance.getTownCache()
                    .getMember(town.getId(), player.getUniqueId()) != null;

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townLevelTasksTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townLevelTasksTuiText.button));
            view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.levelLine,
                    task.currentLevel(), task.systemMaxLevel())));
            view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.openLevelLine,
                    task.progressionMaxLevel())));
            view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.capacityLine,
                    formatLimit(TownManager.outpostLimit(town)),
                    formatLimit(TownManager.outpostLimitPerWorld(town)))));

            if (task.available()) {
                view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.taskLine,
                        task.targetLevel(), TownLevelManager.displayMaterial(task.material()), task.required())));
                view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.progressLine,
                        task.submitted(), task.required(), task.remaining())));
                if (member) {
                    view.add(Line.create()
                            .append(new FunctionalButton(TextUserInterface.townLevelTasksTuiText.submitButton) {
                                @Override
                                public void function() {
                                    TownLevelManager.contributeFromInventory(player, town,
                                            () -> show(player, town.getName(), "1"));
                                }
                            }.needPermission(defaultPermission).build())
                            .append(TextUserInterface.townLevelTasksTuiText.submitDescription));
                } else {
                    view.add(Line.create().append(TextUserInterface.townLevelTasksTuiText.outsiderLine));
                }
            } else if (task.state() == TownLevelManager.State.OPEN_MAX) {
                view.add(Line.create().append(TextUserInterface.townLevelTasksTuiText.openMaxLine));
            } else {
                view.add(Line.create().append(formatString(TextUserInterface.townLevelTasksTuiText.missingLine,
                        task.targetLevel())));
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    public static class TownLevelTasksCui extends ConfigurationPart {
        public String title = "§6✦ §eLevel Task: {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i', List.of(
                "<########",
                "#iiiiiii#",
                "#iiiiiii#",
                "#########",
                "p#######n"
        ));
        public ButtonConfiguration overviewItem = ButtonConfiguration.createMaterial(
                'i', Material.EXPERIENCE_BOTTLE, "§eTown Level",
                List.of("§7Current: §b{0}§7/§b{1}",
                        "§7Member progression open through: §b{2}",
                        "§7Outpost limit: §b{3} total / {4} per world"));
        public ButtonConfiguration taskItem = ButtonConfiguration.createMaterial(
                'i', Material.CHEST, "§6Target Level {0}",
                List.of("§7Required: §e{0} x {1}",
                        "§7Shared progress: §a{2}§7/§a{3}",
                        "§7Remaining: §e{4}"));
        public ButtonConfiguration submitItem = ButtonConfiguration.createMaterial(
                'i', Material.HOPPER, "§aSubmit Task Items",
                List.of("§7Takes matching items from your inventory.",
                        "§7Partial submissions are allowed.",
                        "§a▶ Click to submit"));
        public ButtonConfiguration openMaxItem = ButtonConfiguration.createMaterial(
                'i', Material.EMERALD_BLOCK, "§aCurrently Complete",
                List.of("§7This town has reached the highest level", "§7currently open to members."));
        public ButtonConfiguration missingItem = ButtonConfiguration.createMaterial(
                'i', Material.BARRIER, "§cTask Not Open",
                List.of("§7No task is configured for target level {0}."));
        public ButtonConfiguration outsiderItem = ButtonConfiguration.createMaterial(
                'i', Material.GRAY_DYE, "§7Members Only",
                List.of("§7Only town members may submit task items."));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            TownLevelManager.ProgressSnapshot task = requireSnapshot(town);
            boolean member = CacheManager.instance.getTownCache()
                    .getMember(town.getId(), player.getUniqueId()) != null;

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townLevelTasksCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townLevelTasksCui.listConfiguration, 1);
            view = view.addItem(new NoopButton(ChestUserInterface.townLevelTasksCui.overviewItem)
                    .setLoreArgs(task.currentLevel(), task.systemMaxLevel(), task.progressionMaxLevel(),
                            formatLimit(TownManager.outpostLimit(town)),
                            formatLimit(TownManager.outpostLimitPerWorld(town))));

            if (task.available()) {
                view = view.addItem(new NoopButton(ChestUserInterface.townLevelTasksCui.taskItem)
                        .setDisplayNameArgs(task.targetLevel())
                        .setLoreArgs(TownLevelManager.displayMaterial(task.material()), task.required(),
                                task.submitted(), task.required(), task.remaining()));
                if (member) {
                    view = view.addItem(new ChestButton(ChestUserInterface.townLevelTasksCui.submitItem) {
                        @Override
                        public void onClick(ClickType type) {
                            TownLevelManager.contributeFromInventory(player, town,
                                    () -> show(player, town.getName(), "1"));
                        }
                    });
                } else {
                    view = view.addItem(new NoopButton(ChestUserInterface.townLevelTasksCui.outsiderItem));
                }
            } else if (task.state() == TownLevelManager.State.OPEN_MAX) {
                view = view.addItem(new NoopButton(ChestUserInterface.townLevelTasksCui.openMaxItem));
            } else {
                view = view.addItem(new NoopButton(ChestUserInterface.townLevelTasksCui.missingItem)
                        .setLoreArgs(task.targetLevel()));
            }

            view.setButton(ChestUserInterface.townLevelTasksCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townLevelTasksCui.backButton) {
                        @Override
                        public void onClick(ClickType type) {
                            TownInfo.show(player, town.getName(), "1");
                        }
                    });
            view.open();
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        TownLevelManager.ProgressSnapshot task = requireSnapshot(town);
        Notification.info(sender, formatString(TextUserInterface.townLevelTasksTuiText.levelLine,
                task.currentLevel(), task.systemMaxLevel()));
        Notification.info(sender, formatString(TextUserInterface.townLevelTasksTuiText.openLevelLine,
                task.progressionMaxLevel()));
        if (task.available()) {
            Notification.info(sender, formatString(TextUserInterface.townLevelTasksTuiText.taskLine,
                    task.targetLevel(), TownLevelManager.displayMaterial(task.material()), task.required()));
            Notification.info(sender, formatString(TextUserInterface.townLevelTasksTuiText.progressLine,
                    task.submitted(), task.required(), task.remaining()));
        } else if (task.state() == TownLevelManager.State.OPEN_MAX) {
            Notification.info(sender, TextUserInterface.townLevelTasksTuiText.openMaxLine);
        } else {
            Notification.warn(sender, formatString(TextUserInterface.townLevelTasksTuiText.missingLine,
                    task.targetLevel()));
        }
    }

    private @NotNull TownLevelManager.ProgressSnapshot requireSnapshot(@NotNull TownDOO town)
            throws Exception {
        return prefetched != null ? prefetched : TownLevelManager.snapshot(town);
    }

    private static @NotNull String formatLimit(int limit) {
        return limit == -1 ? "∞" : String.valueOf(limit);
    }

    private static class NoopButton extends ChestButton {
        private NoopButton(ButtonConfiguration config) {
            super(config);
        }

        @Override
        public void onClick(ClickType type) {
        }
    }
}
