package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.doos.TownMemberDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestListView;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.scui.configuration.ListViewConfiguration;
import cn.lunadeer.dominion.utils.stui.ListView;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.FunctionalButton;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import cn.lunadeer.dominion.utils.stui.inputter.InputterRunner;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.formatString;

/**
 * Town bank (plan §13.3): balance display; deposit for members, withdraw
 * for mayor/assistant. Amounts go through the same quantized, guarded
 * TownManager paths as the commands.
 */
public class TownBank extends AbstractUI {

    public static void show(CommandSender sender, String townName, String pageStr) {
        new TownBank().displayByPreference(sender, townName, pageStr);
    }

    public static ListViewButton button(CommandSender sender, String townName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townBankTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, townName, pageStr);
            }
        }.needPermission(defaultPermission);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownBankTuiText extends ConfigurationPart {
        public String title = "Bank of {0}";
        public String button = "BANK";
        public String balanceLine = "§7Balance: §b{0}";
        public String depositButton = "DEPOSIT";
        public String depositHint = "Enter the amount to deposit.";
        public String withdrawButton = "WITHDRAW";
        public String withdrawHint = "Enter the amount to withdraw.";
        public String invalidAmount = "Invalid amount: {0}";
    }

    private static void amountInputter(Player player, String townName, boolean deposit) {
        new InputterRunner(player, deposit
                ? TextUserInterface.townBankTuiText.depositHint
                : TextUserInterface.townBankTuiText.withdrawHint) {
            @Override
            public void run(String input) {
                cn.lunadeer.dominion.utils.scheduler.Scheduler.runTaskAsync(() -> runAsync(input));
            }

            private void runAsync(String input) {
                try {
                    double amount = Double.parseDouble(input.trim());
                    TownDOO town = TownManager.requireTown(townName);
                    if (deposit) {
                        TownManager.deposit(player, town, amount);
                    } else {
                        TownManager.withdraw(player, town, amount);
                    }
                    cn.lunadeer.dominion.utils.scheduler.Scheduler.runTask(
                            () -> show(player, townName, "1"));
                } catch (NumberFormatException e) {
                    Notification.error(player, formatString(
                            TextUserInterface.townBankTuiText.invalidAmount, input));
                } catch (Exception e) {
                    Notification.error(player, e);
                }
            }
        };
    }

    @Override
    protected void showTUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            int page = toIntegrity(args[1], 1);
            TownMemberDOO.TownRole role = TownManager.roleOf(town, player.getUniqueId());
            boolean bypass = cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            boolean manager = TownManager.isManager(town, player.getUniqueId()) || bypass;

            ListView view = ListView.create(10, button(player, town.getName()));
            view.title(formatString(TextUserInterface.townBankTuiText.title, town.getName()));
            view.navigator(Line.create()
                    .append(TownInfo.button(player, town.getName()).build())
                    .append(TextUserInterface.townBankTuiText.button));
            view.add(Line.create().append(formatString(TextUserInterface.townBankTuiText.balanceLine,
                    town.getBalance())));
            if (role != null || bypass) {
                view.add(Line.create().append(new FunctionalButton(TextUserInterface.townBankTuiText.depositButton) {
                    @Override
                    public void function() {
                        amountInputter(player, town.getName(), true);
                    }
                }.needPermission(defaultPermission).build()));
            }
            if (manager) {
                view.add(Line.create().append(new FunctionalButton(TextUserInterface.townBankTuiText.withdrawButton) {
                    @Override
                    public void function() {
                        amountInputter(player, town.getName(), false);
                    }
                }.needPermission(defaultPermission).build()));
            }
            view.showOn(player, page);
        } catch (Exception e) {
            Notification.error(player, e);
        }
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class TownBankCui extends ConfigurationPart {
        public String title = "§6✦ §eBank of {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i',
                List.of(
                        "<########",
                        "#iiiiiii#",
                        "#########",
                        "#########",
                        "p#######n"
                )
        );
        public ButtonConfiguration balanceItem = ButtonConfiguration.createMaterial(
                'i', Material.GOLD_BLOCK, "§6Balance: §e{0}",
                List.of("§7Town bank balance."));
        public ButtonConfiguration depositItem = ButtonConfiguration.createMaterial(
                'i', Material.HOPPER, "§aDeposit",
                List.of("§7Deposit from your wallet.", "§a▶ Click to deposit"));
        public ButtonConfiguration withdrawItem = ButtonConfiguration.createMaterial(
                'i', Material.DROPPER, "§cWithdraw",
                List.of("§7Withdraw to your wallet (managers).", "§c▶ Click to withdraw"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back",
                List.of("§e▶ Click to go back"));
    }

    @Override
    protected void showCUI(Player player, String... args) {
        try {
            TownDOO town = TownManager.requireTown(args[0]);
            TownMemberDOO.TownRole role = TownManager.roleOf(town, player.getUniqueId());
            boolean bypass = cn.lunadeer.dominion.misc.Others.bypassLimit(player);
            boolean manager = TownManager.isManager(town, player.getUniqueId()) || bypass;

            ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
            view.setTitle(formatString(ChestUserInterface.townBankCui.title, town.getName()));
            view.applyListConfiguration(ChestUserInterface.townBankCui.listConfiguration, 1);

            ChestButton balance = new ChestButton(ChestUserInterface.townBankCui.balanceItem) {
                @Override
                public void onClick(ClickType type) {
                }
            };
            balance = balance.setDisplayNameArgs(String.valueOf(town.getBalance()));
            view = view.addItem(balance);

            final ChestListView viewFinal = view;
            if (role != null || bypass) {
                view = view.addItem(new ChestButton(ChestUserInterface.townBankCui.depositItem) {
                    @Override
                    public void onClick(ClickType type) {
                        viewFinal.close();
                        amountInputter(player, town.getName(), true);
                    }
                });
            }
            if (manager) {
                view = view.addItem(new ChestButton(ChestUserInterface.townBankCui.withdrawItem) {
                    @Override
                    public void onClick(ClickType type) {
                        viewFinal.close();
                        amountInputter(player, town.getName(), false);
                    }
                });
            }
            view.setButton(ChestUserInterface.townBankCui.backButton.getSymbol(),
                    new ChestButton(ChestUserInterface.townBankCui.backButton) {
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

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        Notification.info(sender, formatString(TextUserInterface.townBankTuiText.balanceLine, town.getBalance()));
    }
}
