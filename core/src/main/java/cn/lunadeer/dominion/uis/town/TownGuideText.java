package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;

import java.util.List;

/** Shared article content for the inventory, chat and console handbook. */
public class TownGuideText extends ConfigurationPart {
    public String title = "Town Handbook";
    public String button = "HANDBOOK";
    public String pageHelp = "PAGE HELP";
    public String description = "Learn about town roles, templates, trust and land management.";
    public String read = "Click to read; hover over each article card for its instructions.";
    public String back = "Back";
    public String contents = "Contents";
    public String previousPage = "Previous page";
    public String nextPage = "Next page";
    public String pageInfo = "Page {0}/{1}";
    public String openTown = "Open town home";
    public String openGroups = "Open town groups";
    public String openOutposts = "Open outposts";
    public String openTrusts = "Open town trusts";
    public String openTasks = "Open shared level task";
    public String context = "Town: {0} | Permission mode: {1}";
    public String noTown = "You can read every chapter without joining a town.";
    public String templateMode = "Town templates";
    public String legacyMode = "Legacy per-outpost permissions";
    public String exampleTown = "OakTown";
    public String enabled = "enabled";
    public String disabled = "disabled";
    public String unlimited = "unlimited";
    public String unknownTopic = "Unknown chapter: {0}. Showing the contents.";
    public String taskLine = "Level {0}: {1} x {2}";
    public String noTasks = "No item tasks are configured within the open level range.";
    public String dissolveAll = "All town outpost claims are deleted. Blocks and chest contents remain, but their claim protection is removed.";
    public String returnContributed = "Contributed land is returned to its contributor; other town outpost claims are deleted. Blocks and chest contents remain.";

    public Article start = new Article("Joining and creating", "Invitations, starter land and the selection tool.", List.of(
            "Accept an invitation\nAsk a town manager to invite you. Then enter /town join {town} (replace the example name if needed). Invitations can be accepted on another connected server. Joining does not automatically attach your personal land.",
            "Create a town\nUse /town create <name>, or the create button in the main menu. Existing personal land stays personal. A landless founder receives a {founding_size} x {founding_size} x {founding_size} starter outpost around their location; its configured wallet cost is {founding_cost}. Follow the creation confirmation prompt.",
            "Select a remote outpost\nHold {tool}: left-click one block and right-click another to select two corners in the same world. A mayor or assistant can then use Town → Create Outpost. Check the size and town-bank quote before confirming.",
            "Creation restrictions\nRemote outpost creation is {remote_enabled}. Land cannot overlap other claims. Some worlds may prohibit creation for non-admins. Use /town to return to your town or the town list."
    ));
    public Article roles = new Article("Roles and groups", "Management roles are different from building permissions.", List.of(
            "Mayor and assistant\nBoth can manage members, groups and individual town outposts. Only the mayor (or a server administrator) can disband the town; assistants cannot. Ordinary permission groups never grant these management roles.",
            "Member permissions\nAssign members through Town → Groups → select a group → Members. A custom group decides their action permissions; unassigned members use the built-in town-member group. Group creation example: builders Builders.",
            "Outpost managers\nA contributor or appointed outpost administrator can manage certain settings on that outpost. That status alone does not authorize deleting or resizing town land, or disbanding the town. Mayors, assistants and server admins may delete an individual outpost.",
            "Testing permissions\nServer admins and land managers may bypass action restrictions. Test with an ordinary member account. Environment settings (such as explosions) are separate from player-action permissions."
    ));
    public Article templates = new Article("Templates and overrides", "Set town defaults, then make exceptions for one outpost.", List.of(
            "Town-wide defaults\nIn template mode, open Town → Groups → Town Member Template, or a custom group → Template. Changes affect that group's outposts which still inherit the changed permission. They do not automatically change visitor or environment settings.",
            "Three outpost states\nInheritance follows the town template. Local allow and local deny replace it only on this outpost. In the GUI, left-click toggles the local value; right-click restores inheritance. The TUI provides explicit state buttons.",
            "Example: protect a warehouse\nAllow placing blocks in the Builders town template. In the warehouse outpost's corresponding group, set placing blocks to local deny. Other inheriting outposts still allow building. Restore inheritance to follow the template again.",
            "Older towns\nLegacy towns keep separate outpost settings until upgraded. The mayor can use Upgrade Permission Mode on the town home. This is a one-way reset of town-managed outpost group permissions to inheritance. Set and review the town templates afterwards; it cannot be switched back."
    ));
    public Article trust = new Article("Visitors and trust", "Grant a non-member access without adding them to the town.", List.of(
            "Choose the scope\nTrust is currently {trust_enabled}. Town Trust applies across the town; Outpost Trust applies to one outpost. Add a player, then enable the required categories. The four switches are independent.",
            "Build and destroy\nBuild includes placing blocks, planting and spawning vehicles. Destroy includes breaking blocks, harvesting and destroying vehicles. Enable only what your friend needs.",
            "Interact and item use\nInteract includes doors, buttons, containers and hoppers: it is broader than opening a chest. Item use includes crafting, trading, feeding, picking up and dropping items. These are category grants, not single-action switches.",
            "Off is not a ban\nTrust adds permission for non-members. An off switch gives no extra grant; legacy member/group or visitor permissions may still allow the action. Trust does not override a town member group's denial.",
            "Local trust takes precedence\nIf an outpost trust record exists, its whole record is used instead of town trust. Turning all four switches off does not restore town trust. Remove the outpost record to fall back to town trust.",
            "Example: let a visitor open chests\nOpen the target Outpost → Trust, add your friend and enable Interact. This also grants the other interactions in that category. Review visitor permissions too if you need to restrict everyone else."
    ));
    public Article land = new Article("Managing land", "Creation, attachment, return and deletion have different effects.", List.of(
            "Attach or return\nUse Town → Attach, or /town attach <land>, to contribute existing personal land. Leaving the town does not take that land back. The mayor may return eligible contributed, non-founding land with /town release <land>; follow its confirmation. OPs can arbitrate other non-founding outposts.",
            "Delete one outpost\nMayors, assistants and server admins can use Delete Outpost in its management page, then confirm. Only the selected town claim and its permissions are removed. Child claims and the town remain; there is no refund. Blocks and items are not erased.",
            "Disband a town\nOnly the mayor or a server administrator can do this, through the existing confirmation flow. Current server policy: {disband_policy} Review the preview before confirming.",
            "Sizes and height\nEach horizontal edge may be at most {max_xz}; minimum X/Y/Z sizes are {min_x}/{min_height}/{min_z}. Configured Y bounds: {min_y} to {max_y}; maximum height: {max_height}. World bounds also apply. Historical excessive height may remain when resizing without changing the Y range.",
            "Area pricing\nTown outpost tier pricing is {pricing_enabled}. It uses X × Z area, not height. The first 250000 square blocks are free; successive area bands up to 1000000 / 2250000 / 4000000 cost {price_1} / {price_2} / {price_3} per square block. Bands are added, not repriced as a whole.",
            "Resize and refunds\nRemote creation and paid expansion charge the town bank. Contraction refunds only eligible tracked paid value, at rate {refund_rate}; historical unpaid area is not refunded. The creation/resize quote is authoritative, including any administrator exemption."
    ));
    public Article levels = new Article("Levels and shared tasks", "Contribute together and unlock additional outposts.", List.of(
            "Outpost limits\nLevel 1 allows {base_limit} total outposts and {base_world_limit} per world, with {per_level} additional slots per level where a limit exists. Founding and contributed land count too. Existing land is retained if a limit is reduced; new creation and attachment check the limit.",
            "Shared contributions\nOpen Town → Town Level to see the current shared task. Members submit matching items from their inventory; contributions are consumed and accumulate for the entire town, including across servers. Completing the requirement advances the level.",
            "Open levels\nMember progression is open through level {open_level}; the system maximum is {max_level}. An absent intermediate task blocks progression. OPs can set a level within the system maximum.",
            "Configured item tasks\n{tasks}"
    ));
    public Article faq = new Article("Troubleshooting", "What to check when settings seem ineffective.", List.of(
            "A member cannot build\nCheck the affected outpost, their town group, its template and any local override. For non-members, check outpost/town trust and visitor permissions instead. Group permissions, management roles and environment settings serve different purposes.",
            "A template change did nothing\nCheck that the town is in template mode, that you edited the player's group, and that this outpost inherits the flag. Local allow/deny is intentionally retained after template edits.",
            "Working across servers\nCheck the server and world labels on outpost entries. Invitations can cross servers; remote teleportation requires the server's cross-server connection to be configured. Protection changes on another server may take time to refresh. Creation and resizing require the relevant local world.",
            "Other restrictions\nAnother protection plugin or a world rule can also block an action. This handbook explains Dominion's rules; it does not diagnose the effective permission of a particular player. Ask a town manager to review the relevant settings."
    ));

    public static class Article extends ConfigurationPart {
        public String title = "";
        public String summary = "";
        public List<String> sections = List.of();

        public Article() { }

        public Article(String title, String summary, List<String> sections) {
            this.title = title;
            this.summary = summary;
            this.sections = sections;
        }
    }
}
