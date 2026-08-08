package cn.lunadeer.dominion.uis.dominion.manage;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.configuration.uis.ChestUserInterface;
import cn.lunadeer.dominion.configuration.uis.TextUserInterface;
import cn.lunadeer.dominion.events.dominion.modify.DominionReSizeEvent;
import cn.lunadeer.dominion.inputters.ResizeDominionInputter;
import cn.lunadeer.dominion.uis.AbstractUI;
import cn.lunadeer.dominion.uis.MainMenu;
import cn.lunadeer.dominion.uis.dominion.DominionList;
import cn.lunadeer.dominion.uis.dominion.DominionManage;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import cn.lunadeer.dominion.utils.scui.ChestButton;
import cn.lunadeer.dominion.utils.scui.ChestUserInterfaceManager;
import cn.lunadeer.dominion.utils.scui.ChestView;
import cn.lunadeer.dominion.utils.scui.configuration.ButtonConfiguration;
import cn.lunadeer.dominion.utils.stui.ListView;
import cn.lunadeer.dominion.utils.stui.components.Line;
import cn.lunadeer.dominion.utils.stui.components.buttons.ListViewButton;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.Arrays;
import java.util.List;

import static cn.lunadeer.dominion.Dominion.defaultPermission;
import static cn.lunadeer.dominion.misc.Asserts.assertDominionManage;
import static cn.lunadeer.dominion.misc.Converts.toDominionDTO;
import static cn.lunadeer.dominion.utils.Misc.formatString;

public class SetSize extends AbstractUI {

    // Direction data structure for better organization
    private static final List<DirectionInfo> DIRECTIONS = Arrays.asList(
            new DirectionInfo(DominionReSizeEvent.DIRECTION.NORTH, () -> TextUserInterface.setSizeTuiText.north),
            new DirectionInfo(DominionReSizeEvent.DIRECTION.SOUTH, () -> TextUserInterface.setSizeTuiText.south),
            new DirectionInfo(DominionReSizeEvent.DIRECTION.WEST, () -> TextUserInterface.setSizeTuiText.west),
            new DirectionInfo(DominionReSizeEvent.DIRECTION.EAST, () -> TextUserInterface.setSizeTuiText.east),
            new DirectionInfo(DominionReSizeEvent.DIRECTION.UP, () -> TextUserInterface.setSizeTuiText.up),
            new DirectionInfo(DominionReSizeEvent.DIRECTION.DOWN, () -> TextUserInterface.setSizeTuiText.down)
    );

    public static void show(CommandSender sender, String dominionName) {
        new SetSize().displayByPreference(sender, dominionName);
    }

    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ TUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class SetSizeTuiText extends ConfigurationPart {
        public String title = "Resize {0}";
        public String button = "RESIZE";
        public String north = "North(z-)";
        public String south = "South(z+)";
        public String west = "West(x-)";
        public String east = "East(x+)";
        public String up = "Up(y+)";
        public String down = "Down(y-)";
    }

    public static ListViewButton button(CommandSender sender, String dominionName) {
        return (ListViewButton) new ListViewButton(TextUserInterface.setSizeTuiText.button) {
            @Override
            public void function(String pageStr) {
                show(sender, dominionName);
            }
        }.needPermission(defaultPermission);
    }

    @Override
    protected void showTUI(Player player, String... args) {
        String dominionName = args[0];
        DominionDTO dominion = toDominionDTO(dominionName);
        assertDominionManage(player, dominion);

        ListView view = createTUIView(player, dominion);
        addDirectionButtons(view, player, dominion.getName());
        view.showOn(player, 1);
    }

    private ListView createTUIView(Player player, DominionDTO dominion) {
        ListView view = ListView.create(10, button(player, dominion.getName()));
        view.title(formatString(TextUserInterface.setSizeTuiText.title, dominion.getName()));
        view.navigator(createNavigationLine(player, dominion.getName()));
        return view;
    }

    private Line createNavigationLine(Player player, String dominionName) {
        return Line.create()
                .append(MainMenu.button(player).build())
                .append(DominionList.button(player).build())
                .append(DominionManage.button(player, dominionName).build())
                .append(Info.button(player, dominionName).build())
                .append(TextUserInterface.setSizeTuiText.button);
    }

    private void addDirectionButtons(ListView view, Player player, String dominionName) {
        for (DirectionInfo directionInfo : DIRECTIONS) {
            view.add(createDirectionLine(player, dominionName, directionInfo));
        }
    }

    private Line createDirectionLine(Player player, String dominionName, DirectionInfo directionInfo) {
        return Line.create()
                .append(directionInfo.getDisplayName())
                .append(ResizeDominionInputter.createExpandTuiButtonOn(player, dominionName, directionInfo.direction()).build())
                .append(ResizeDominionInputter.createContractTuiButtonOn(player, dominionName, directionInfo.direction()).build());
    }

    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ TUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ CUI ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    public static class SetSizeCui extends ConfigurationPart {
        public String title = "§6✦ §2§lResize {0} §6✦";
        public List<String> layout = List.of(
                "<##N###U#",
                "###n###u#",
                "#Ww#eE###",
                "###s###d#",
                "###S###D#"
        );

        public ButtonConfiguration backButton = ButtonConfiguration.createMaterial(
                '<', Material.RED_STAINED_GLASS_PANE,
                "§c« Back to Dominion Management",
                List.of(
                        "§7Return to the dominion",
                        "§7management menu.",
                        "",
                        "§e▶ Click to go back"
                )
        );

        public ButtonConfiguration addNorthButton = ButtonConfiguration.createMaterial(
                'N', Material.LIME_DYE,
                "§6🧭 §eExpand North (Z-)",
                List.of(
                        "§7Expand the dominion to the north",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration addSouthButton = ButtonConfiguration.createMaterial(
                'S', Material.LIME_DYE,
                "§6🧭 §eExpand South (Z+)",
                List.of(
                        "§7Expand the dominion to the south",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration addWestButton = ButtonConfiguration.createMaterial(
                'W', Material.LIME_DYE,
                "§6🧭 §eExpand West (X-)",
                List.of(
                        "§7Expand the dominion to the west",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration addEastButton = ButtonConfiguration.createMaterial(
                'E', Material.LIME_DYE,
                "§6🧭 §eExpand East (X+)",
                List.of(
                        "§7Expand the dominion to the east",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration addUpButton = ButtonConfiguration.createMaterial(
                'U', Material.LIME_DYE,
                "§6🧭 §eExpand Up (Y+)",
                List.of(
                        "§7Expand the dominion upwards",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration addDownButton = ButtonConfiguration.createMaterial(
                'D', Material.LIME_DYE,
                "§6🧭 §eExpand Down (Y-)",
                List.of(
                        "§7Expand the dominion downwards",
                        "§7with input size.",
                        "",
                        "§a▶ Click to expand"
                )
        );

        public ButtonConfiguration contractNorthButton = ButtonConfiguration.createMaterial(
                'n', Material.RED_DYE,
                "§6🧭 §cContract North (Z-)",
                List.of(
                        "§7Contract the dominion from the north",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );

        public ButtonConfiguration contractSouthButton = ButtonConfiguration.createMaterial(
                's', Material.RED_DYE,
                "§6🧭 §cContract South (Z+)",
                List.of(
                        "§7Contract the dominion from the south",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );

        public ButtonConfiguration contractWestButton = ButtonConfiguration.createMaterial(
                'w', Material.RED_DYE,
                "§6🧭 §cContract West (X-)",
                List.of(
                        "§7Contract the dominion from the west",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );

        public ButtonConfiguration contractEastButton = ButtonConfiguration.createMaterial(
                'e', Material.RED_DYE,
                "§6🧭 §cContract East (X+)",
                List.of(
                        "§7Contract the dominion from the east",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );

        public ButtonConfiguration contractUpButton = ButtonConfiguration.createMaterial(
                'u', Material.RED_DYE,
                "§6🧭 §cContract Up (Y+)",
                List.of(
                        "§7Contract the dominion upwards",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );

        public ButtonConfiguration contractDownButton = ButtonConfiguration.createMaterial(
                'd', Material.RED_DYE,
                "§6🧭 §cContract Down (Y-)",
                List.of(
                        "§7Contract the dominion downwards",
                        "§7with input size.",
                        "",
                        "§c▶ Click to contract"
                )
        );
    }

    @Override
    protected void showCUI(Player player, String... args) {
        String dominionName = args[0];
        DominionDTO dominion = toDominionDTO(dominionName);
        assertDominionManage(player, dominion);

        ChestView view = createCUIView(player, dominion);
        setupCUIButtons(view, player, dominion);
        view.open();
    }

    private ChestView createCUIView(Player player, DominionDTO dominion) {
        ChestView view = ChestUserInterfaceManager.getInstance().getViewOf(player);
        view.setTitle(formatString(ChestUserInterface.setSizeCui.title, dominion.getName()));
        view.setLayout(ChestUserInterface.setSizeCui.layout);
        return view;
    }

    private void setupCUIButtons(ChestView view, Player player, DominionDTO dominion) {
        setupBackButton(view, player, dominion);
        setupDirectionButtons(view, player, dominion);
    }

    private void setupBackButton(ChestView view, Player player, DominionDTO dominion) {
        view.setButton(ChestUserInterface.setSizeCui.backButton.getSymbol(),
                new ChestButton(ChestUserInterface.setSizeCui.backButton) {
                    @Override
                    public void onClick(ClickType type) {
                        DominionManage.show(player, dominion.getName(), "1");
                    }
                }
        );
    }

    private void setupDirectionButtons(ChestView view, Player player, DominionDTO dominion) {
        // Expand buttons
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addNorthButton, DominionReSizeEvent.DIRECTION.NORTH);
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addSouthButton, DominionReSizeEvent.DIRECTION.SOUTH);
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addWestButton, DominionReSizeEvent.DIRECTION.WEST);
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addEastButton, DominionReSizeEvent.DIRECTION.EAST);
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addUpButton, DominionReSizeEvent.DIRECTION.UP);
        setupExpandButton(view, player, dominion, ChestUserInterface.setSizeCui.addDownButton, DominionReSizeEvent.DIRECTION.DOWN);

        // Contract buttons
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractNorthButton, DominionReSizeEvent.DIRECTION.NORTH);
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractSouthButton, DominionReSizeEvent.DIRECTION.SOUTH);
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractWestButton, DominionReSizeEvent.DIRECTION.WEST);
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractEastButton, DominionReSizeEvent.DIRECTION.EAST);
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractUpButton, DominionReSizeEvent.DIRECTION.UP);
        setupContractButton(view, player, dominion, ChestUserInterface.setSizeCui.contractDownButton, DominionReSizeEvent.DIRECTION.DOWN);
    }

    private void setupExpandButton(ChestView view, Player player, DominionDTO dominion, ButtonConfiguration buttonConfig, DominionReSizeEvent.DIRECTION direction) {
        view.setButton(buttonConfig.getSymbol(),
                new ChestButton(buttonConfig) {
                    @Override
                    public void onClick(ClickType type) {
                        ResizeDominionInputter.createExpandOn(player, dominion.getName(), direction);
                        view.close();
                    }
                }
        );
    }

    private void setupContractButton(ChestView view, Player player, DominionDTO dominion, ButtonConfiguration buttonConfig, DominionReSizeEvent.DIRECTION direction) {
        view.setButton(buttonConfig.getSymbol(),
                new ChestButton(buttonConfig) {
                    @Override
                    public void onClick(ClickType type) {
                        ResizeDominionInputter.createContractOn(player, dominion.getName(), direction);
                        view.close();
                    }
                }
        );
    }

    // Helper class to organize direction information
    private record DirectionInfo(DominionReSizeEvent.DIRECTION direction,
                                 java.util.function.Supplier<String> displayNameSupplier) {

        public String getDisplayName() {
            return displayNameSupplier.get();
        }
    }


    // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ CUI ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    // ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Console ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓

    @Override
    protected void showConsole(CommandSender sender, String... args) throws Exception {
        Notification.warn(sender, Language.consoleText.inGameOnly);
    }
}
