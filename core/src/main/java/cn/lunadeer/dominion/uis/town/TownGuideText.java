package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;

import java.util.List;

/** Each section is one complete lesson, shared by the chest and chat readers. */
public class TownGuideText extends ConfigurationPart {
    public String title = "Town Handbook";
    public String button = "HANDBOOK";
    public String pageHelp = "PAGE HELP";
    public String description = "Start with joining a town, then learn about building, permissions and land.";
    public String read = "Click to start. Hover over the paper to read; use the arrows to continue.";
    public String back = "Back";
    public String contents = "Contents";
    public String previousPage = "Previous lesson";
    public String nextPage = "Next lesson";
    public String nextChapter = "Next chapter";
    public String pageInfo = "Lesson {0}/{1}";
    public String openTown = "Open town home";
    public String openGroups = "Open member permissions";
    public String openOutposts = "Open outposts";
    public String openTrusts = "Open town trusts";
    public String openTasks = "Open shared level task";
    public String context = "Town: {0}";
    public String noTown = "You can read without joining a town. New here? Start with the first chapter.";
    public String exampleTown = "OakTown";
    public String stick = "Stick";
    public String enabled = "enabled";
    public String disabled = "disabled";
    public String unlimited = "unlimited";
    public String unknownTopic = "Unknown chapter: {0}. Showing the contents.";
    public String taskLine = "Level {0}: {1} x {2}";
    public String noTasks = "There are no available level tasks.";
    public String dissolveAll = "All town outposts lose their claim protection. Buildings and chest contents remain.";
    public String returnContributed = "Contributed land returns to its contributor; other town outposts lose protection. Buildings and chest contents remain.";

    public Article start = new Article("Getting started", "Join friends or start a town of your own.", List.of(
            "What is a town?\nA town is a team of players. An outpost is one of its protected areas, such as a neighborhood, farm or warehouse.\nA town can have several outposts; they do not need to touch. Use /town to open the menu.",
            "Accept an invitation\nAsk a mayor or assistant to invite you, then enter the join command in the invitation.\nExample: /town join {town}\nYou can accept on another connected server. Joining does not give your personal land to the town.",
            "Create your own town\nChoose Create Town in the main menu, or use /town create <name>.\nIf you have no land, you receive a {founding_size} x {founding_size} x {founding_size} starter outpost at your location for {founding_cost} from your wallet. Existing personal land is not attached automatically.",
            "What next?\nMembers can ask their mayor for a place to live and suitable permissions. Mayors can start by inviting players and arranging groups.\nRead Managing Town Land to claim more space, or How Inheritance Works to learn about building permissions.",
            "Find a town or dominion\nClick Search in a town or dominion list and enter part of its name. Example: Oak finds OakTown. English case does not matter.\nUse /town search <keyword> to find towns, or /dom search <keyword> for your owned and managed land. In a town's outpost list, search stays within that town."
    ));

    public Article roles = new Article("Who can do what?", "Learn about town roles and member groups.", List.of(
            "What is a permission?\nA permission decides whether someone can do a particular action. Placing blocks, breaking blocks and opening chests have their own settings.\nBeing allowed to build does not necessarily mean you can open chests.",
            "Mayor, assistant and member\nMayors and assistants manage members, groups and individual outposts. A regular member's group decides what actions they can perform.\nOnly a mayor or server administrator can disband the whole town. Assistants cannot.",
            "What is a group?\nA group gives several members the same permissions. For example, put your builders in a Builders group and allow that group to build.\nUnassigned members use Town Members (Default Group). Joining Builders does not make someone an assistant.",
            "Assign members\nOpen Town → Member Permissions, create a group, then open its Members page and choose existing town members.\nExample group input: builders Builders\nThe first name is used in commands; the second is shown in menus.",
            "Creator or contributor?\nLand created directly for the town shows its creator. Attached personal land shows its former owner as contributor.\nThey and appointed outpost admins can manage some settings on that land. Those titles alone do not allow deleting or resizing town land."
    ));

    public Article templates = new Article("How inheritance works", "Town defaults first, then exceptions for one outpost.", List.of(
            "What are town-wide default permissions?\nTown-wide default permissions are a shared template for outposts. Each member group has its own template.\nAllow placing blocks in the Builders template to let builders work on several outposts without setting each one separately.",
            "What does inherit mean?\nInherit means this outpost follows the town template.\nIf the template allows placing blocks, so does this outpost. Change the template to deny, and the outpost follows.\nInherit itself means neither allow nor deny: check the template's current setting.",
            "Example: build in two places\nAlex belongs to Builders. The town has a neighborhood and a farm.\nAllow placing blocks in the Builders template and leave both outposts on inherit.\nAlex can now place blocks in both places.",
            "Example: protect the warehouse\nThe town adds a warehouse where builders should not alter the building.\nIn the warehouse's Builders permissions, set placing blocks to local deny.\nThis is an override: a separate setting for this permission, this group and this outpost only.",
            "What if the template changes?\nNow turn placing blocks off in the Builders template, then on again.\nThe neighborhood and farm follow each change. The warehouse still denies it.\nOther warehouse permissions left on inherit continue following the template.",
            "Restore inheritance\nWhen builders can work on the warehouse again, restore inherit for placing blocks there.\nThe warehouse immediately follows the template's current value and will follow future changes too.",
            "Where to change settings\nFor the whole town: Town → Member Permissions → Town Members (Default Group), or a custom group → Town-wide Default Permissions.\nFor one outpost: open its matching group permissions. In the chest menu, left-click toggles local allow/deny; right-click restores inherit. In chat, use the state buttons.\nVisitor and environment settings are separate.",
            "Older towns\nIf the town home offers Upgrade Permission Mode, the mayor needs to upgrade once to use templates.\nThis clears the old outpost group settings and resets them to inherit. It cannot be undone. Discuss it with members first, then confirm and set each town template."
    ));

    public Article trust = new Article("Helping friends visit", "Give friends access without making them members.", List.of(
            "When to use trust\nTrust is for friends who want to help or collect supplies without joining your town.\nTown Trust applies across the town; Outpost Trust applies to one place. Trust is currently {trust_enabled}.\nFor town members, use their member group instead.",
            "The four switches\nEach switch can be changed separately.\nBuild: placing, planting and placing vehicles.\nDestroy: breaking, harvesting and breaking vehicles.\nInteract: doors, buttons, chests and more.\nItem use: crafting, trading, feeding, picking up and dropping items.",
            "Example: collecting supplies\nOpen Warehouse Outpost → Trust, add your friend and enable Interact.\nThis also allows doors, buttons and other interactions. Leave Build and Destroy off if you do not want to grant extra access to alter the building.",
            "Why can they act with trust off?\nOff means trust gives no extra permission.\nIf visitors can already open doors, turning off a friend's Interact trust will not stop them. To restrict visitors in general, change the outpost's visitor permissions.",
            "Trust in both places\nIf a friend has outpost trust, that outpost uses those settings without adding town trust.\nFor example, town trust allows building but warehouse trust allows only Interact. They do not receive building access from trust in the warehouse.",
            "Use town trust again\nRemove the friend's trust entry on that outpost to use town trust again.\nTurning all four switches off still leaves a separate outpost setting in place."
    ));

    public Article land = new Article("Managing town land", "Select land, contribute old claims and understand costs.", List.of(
            "Select a new outpost\nA mayor or assistant holds {tool}, left-clicks one block and right-clicks another to select opposite corners in the same world.\nOpen Town → Create Outpost, review the area and quote, then confirm. New outpost creation is {remote_enabled}.",
            "Where and how big?\nEach horizontal edge can be at most {max_xz} blocks. Minimum length and width: {min_x} and {min_z}; minimum height: {min_height}.\nY range: {min_y} to {max_y}; maximum height: {max_height}. World height limits also apply.\nClaims cannot overlap. Some worlds may forbid creation.",
            "Creation and expansion costs\nTier pricing is {pricing_enabled}. It counts length x width, not height.\nThe first 250000 square blocks are free. The bands up to 1000000, 2250000 and 4000000 cost {price_1}, {price_2} and {price_3} coins per square block.\nNew outposts and expansion use the town bank. Review the quote before confirming.",
            "Example: one square kilometer\n500 x 500 blocks is 250000 square blocks; 1000 x 1000 is one square kilometer.\nWith tier pricing enabled, the latter has 750000 square blocks above the free allowance, charged at {price_1} each.\nExpanding further does not reprice the entire claim at the higher rate.",
            "Shrinking and refunds\nOnly recorded paid area can receive a {refund_rate} refund. Free area has nothing to refund.\nChanging height alone does not change the area price or refund. Old tall claims can keep their existing Y range while resizing length and width.",
            "Contribute personal land\nChoose Town → Attach, or /town attach <land>. The claim becomes town land and its former owner is shown as contributor.\nLeaving the town does not take it back. A mayor can use /town release <land> for eligible contributed land and follow the confirmation. Founding land cannot be returned this way.",
            "Delete land or disband?\nA mayor or assistant can delete one outpost from its management page after confirmation. No refund; buildings, chest contents, child claims and the town remain.\nOnly a mayor or server administrator can disband the town. Current result: {disband_policy}\nRead which claims are affected before confirming."
    ));

    public Article levels = new Article("Level up together", "Share materials and unlock more outpost slots.", List.of(
            "Why level up?\nHigher levels allow more outposts. Level 1 allows {base_limit} total and {base_world_limit} per world; each level adds {per_level} slots where a limit exists.\nFounding and contributed land count too. Existing outposts are not deleted for exceeding a limit.",
            "Join a shared task\nOpen Town → Town Level to see the next requirement. Put the materials in your inventory and click Submit.\nSubmitted items are consumed. Progress belongs to the whole town, including across connected servers. Completing the task levels up the town.",
            "Example: share the work\nSuppose a task needs 9 netherite ingots. You submit 4 and a friend submits 5 to complete it together.\nOne player does not need to provide everything at once. Check the task page for the required material and remaining amount.",
            "Which levels are open?\nShared tasks currently reach level {open_level}. Afterwards, keep playing while you wait for more tasks.\nCurrent materials:\n{tasks}"
    ));

    public Article faq = new Article("When something goes wrong", "Building problems, unchanged templates and other servers.", List.of(
            "Why can't I place blocks?\nCheck which outpost you are in and ask the mayor to check your group.\nFor templates, check placing blocks in your group template and any separate denial on this outpost.\nIf you are not a member, ask about visitor permissions or suitable trust.",
            "Why did the template change nothing?\nCheck that you edited the player's group template and that the outpost permission says Inherit.\nLocal allow or deny stays separate until you restore inheritance. Older towns need their mayor to upgrade permission mode first.",
            "The mayor can, but members cannot\nMayors, outpost managers and server administrators can have direct management access. Their actions do not show what regular members can do.\nTo test whether Builders can open chests, ask a regular member of Builders to try.",
            "Playing on another server\nRead the server and world labels on the outpost. Invitations work across connected servers; use outpost teleport if the server supports cross-server travel.\nCreating or resizing requires the relevant world. Permission changes on other servers may take time. If an action still fails, tell your mayor the location and message."
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
