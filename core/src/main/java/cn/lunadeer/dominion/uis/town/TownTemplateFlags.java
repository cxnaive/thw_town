package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.doos.TownDOO;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.managers.TownPermissionManager;
import cn.lunadeer.dominion.misc.DominionException;
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
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Converts.toIntegrity;
import static cn.lunadeer.dominion.utils.Misc.foldLore2Line;
import static cn.lunadeer.dominion.utils.Misc.formatString;
import static cn.lunadeer.dominion.utils.Misc.formatStringList;

/** Town-wide default permission template for @town or a custom town group. */
public class TownTemplateFlags extends AbstractUI {

    private final Map<String, Boolean> values = new HashMap<>();

    public static void show(CommandSender sender, String townName, String groupName, String pageStr) {
        if (!(sender instanceof Player player)) {
            new TownTemplateFlags().displayByPreference(sender, townName, groupName, pageStr);
            return;
        }
        TownTemplateFlags ui = new TownTemplateFlags();
        TownUI.prefetchThenRender(player,
                () -> ui.prefetch(townName, groupName),
                () -> ui.displayByPreference(player, townName, groupName, pageStr));
    }

    public static class TownTemplateFlagsTuiText extends ConfigurationPart {
        public String title = "Town-wide Default Permissions: {0}";
        public String button = "TOWN-WIDE DEFAULT PERMISSIONS";
        public String description = "Outposts use these permissions by default; individual outposts can be set separately.";
        public String modeHint = "§7Changes apply to every outpost still using inheritance.";
    }

    public static class TownTemplateFlagsCui extends ConfigurationPart {
        public String title = "§6✦ §eTown-wide Default Permissions: {0} §6✦";
        public ListViewConfiguration listConfiguration = new ListViewConfiguration(
                'i', List.of("<########", "#iiiiiii#", "#iiiiiii#", "#iiiiiii#", "p#######n"));
        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE, "§c« Back to Member Permission Groups",
                List.of("§e▶ Click to go back"));
        public String flagItemName = "§7Permission: §e{0}";
        public String flagStateTrue = "§a§l[ALLOW]";
        public String flagStateFalse = "§c§l[DENY]";
        public List<String> flagItemLore = List.of(
                "§7Town-wide default: {0}", "§7{1}", "§7{2}", "", "§e▶ Click to toggle");
    }

    private void prefetch(String townName, String groupName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (!town.isTemplatePermissionMode()) {
            throw new DominionException(Language.townManagerText.templateModeRequired, town.getName());
        }
        int groupKey = TownManager.townTemplateGroupKey(town, groupName);
        values.putAll(TownPermissionManager.getTemplateValues(town, groupKey));
    }

    private static TownDOO visibleTown(Player player, String townName) throws Exception {
        TownDOO town = TownManager.requireTown(townName);
        if (TownManager.roleOf(town, player.getUniqueId()) == null
                && !cn.lunadeer.dominion.misc.Others.bypassLimit(player)) {
            throw new DominionException(Language.townManagerText.notInTown, town.getName());
        }
        if (!town.isTemplatePermissionMode()) {
            throw new DominionException(Language.townManagerText.templateModeRequired, town.getName());
        }
        return town;
    }

    public static ListViewButton button(Player player, String townName, String groupName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.townTemplateFlagsTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(player, townName, groupName, pageStr);
            }
        }.needPermission(defaultPermission)
                .setHoverText(TextUserInterface.townTemplateFlagsTuiText.description);
    }

    private boolean value(PriFlag flag) {
        return values.getOrDefault(flag.getFlagName(), flag.getDefaultValue());
    }

    @Override
    protected void showTUI(Player player, String... args) throws Exception {
        TownDOO town = visibleTown(player, args[0]);
        String groupName = args[1];
        boolean manager = TownManager.isManager(town, player.getUniqueId())
                || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
        ListView view = ListView.create(10, button(player, town.getName(), groupName));
        view.title(formatString(TextUserInterface.townTemplateFlagsTuiText.title,
                TownManager.townTemplateGroupDisplayName(town, groupName)));
        view.navigator(Line.create().append(TownInfo.button(player, town.getName()).build())
                .append(TownGroups.button(player, town.getName()).build())
                .append(TextUserInterface.townTemplateFlagsTuiText.button)
                .append(TownGuide.button(player, TownGuide.Topic.TEMPLATES, town.getId(),
                        () -> show(player, town.getName(), groupName, args[2])).build()));
        view.add(Line.create().append(TextUserInterface.townTemplateFlagsTuiText.modeHint));
        for (PriFlag flag : TownPermissionManager.getManageableTemplateFlags()) {
            boolean current = value(flag);
            Line line = Line.create();
            if (manager) {
                line.append(new FunctionalButton(current ? "☑" : "☐") {
                    @Override
                    public void function() {
                        TownUI.action(player,
                                () -> TownManager.setTownTemplateFlag(player, town, groupName,
                                        flag, !current),
                                () -> show(player, town.getName(), groupName, args[2]));
                    }
                }.needPermission(defaultPermission).build());
            } else {
                line.append(current ? "§a☑ " : "§c☐ ");
            }
            line.append(Component.text(flag.getDisplayName())
                    .hoverEvent(Component.text(flag.getDescription())));
            view.add(line);
        }
        view.showOn(player, toIntegrity(args[2], 1));
    }

    @Override
    protected void showCUI(Player player, String... args) throws Exception {
        TownDOO town = visibleTown(player, args[0]);
        String groupName = args[1];
        boolean manager = TownManager.isManager(town, player.getUniqueId())
                || cn.lunadeer.dominion.misc.Others.bypassLimit(player);
        ChestListView view = ChestUserInterfaceManager.getInstance().getListViewOf(player);
        view.setTitle(formatString(ChestUserInterface.townTemplateFlagsCui.title,
                TownManager.townTemplateGroupDisplayName(town, groupName)));
        view.applyListConfiguration(ChestUserInterface.townTemplateFlagsCui.listConfiguration,
                toIntegrity(args[2], 1));
        view.setButton(ChestUserInterface.townTemplateFlagsCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.townTemplateFlagsCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        TownGroups.show(player, town.getName(), "1");
                    }
                });
        for (PriFlag flag : TownPermissionManager.getManageableTemplateFlags()) {
            boolean current = value(flag);
            String state = current ? ChestUserInterface.townTemplateFlagsCui.flagStateTrue
                    : ChestUserInterface.townTemplateFlagsCui.flagStateFalse;
            List<String> description = foldLore2Line(flag.getDescription(), 30);
            ButtonConfiguration config = ButtonConfiguration.createMaterial(
                    ChestUserInterface.townTemplateFlagsCui.listConfiguration.itemSymbol.charAt(0),
                    flag.getMaterial(),
                    formatString(ChestUserInterface.townTemplateFlagsCui.flagItemName,
                            flag.getDisplayName()),
                    formatStringList(ChestUserInterface.townTemplateFlagsCui.flagItemLore,
                            state, description.get(0), description.get(1)));
            view.addItem(new ChestButton(config) {
                @Override
                public void onClick(ClickType type) {
                    if (!manager) return;
                    TownUI.action(player,
                            () -> TownManager.setTownTemplateFlag(player, town, groupName,
                                    flag, !current),
                            () -> show(player, town.getName(), groupName, args[2]));
                }
            });
        }
        TownGuide.addHelpButton(view, player, TownGuide.Topic.TEMPLATES, town.getId(),
                () -> show(player, town.getName(), groupName, args[2]));
        view.open();
    }

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        TownDOO town = TownManager.requireTown(args[0]);
        int groupKey = TownManager.townTemplateGroupKey(town, args[1]);
        Map<String, Boolean> template = TownPermissionManager.getTemplateValues(town, groupKey);
        for (PriFlag flag : TownPermissionManager.getManageableTemplateFlags()) {
            Notification.info(sender, "{0}: {1}", flag.getFlagName(),
                    template.get(flag.getFlagName()));
        }
    }
}
