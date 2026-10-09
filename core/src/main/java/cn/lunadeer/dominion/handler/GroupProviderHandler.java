package cn.lunadeer.dominion.handler;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.GroupDTO;
import cn.lunadeer.dominion.api.dtos.MemberDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.api.dtos.flag.PriFlag;
import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.managers.TownManager;
import cn.lunadeer.dominion.misc.DominionException;
import cn.lunadeer.dominion.doos.GroupDOO;
import cn.lunadeer.dominion.doos.MemberDOO;
import cn.lunadeer.dominion.events.group.*;
import cn.lunadeer.dominion.providers.GroupProvider;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

import static cn.lunadeer.dominion.misc.Asserts.*;


public class GroupProviderHandler extends GroupProvider {

    public static class GroupProviderHandlerText extends ConfigurationPart {
        public String ownerOnly = "Only the owner can manage admin group.";
        public String setFlagSuccess = "Set group {0} flag {1} to {2} successfully.";
        public String setFlagFailed = "Failed to set group flag, reason: {3}";

        public String createGroupSuccess = "Group {0} created successfully.";
        public String createGroupFailed = "Failed to create group, reason: {0}";

        public String townGroupReserved = "The @town group is managed by the town system and cannot be deleted or renamed.";
        public String townGroupMirrorReserved = "Town-managed group mirrors cannot be deleted, renamed, or have members edited here; manage them from the town.";
        public String townGroupMembersImplicit = "Members without a custom group use the default group. Open Town → Member Permissions to assign groups.";
        public String townOutpostNoCustomGroups = "Town land uses shared member permission groups. Open Town → Member Permissions to create and assign groups.";
        public String deleteGroupSuccess = "Group {0} deleted successfully.";
        public String deleteGroupFailed = "Failed to delete group, reason: {0}";

        public String renameGroupSuccess = "Group {0} renamed to {1} successfully.";
        public String renameGroupFailed = "Failed to rename group, reason: {0}";

        public String addMemberSuccess = "Member {0} added to group {1} successfully.";
        public String addMemberFailed = "Failed to add member to group, reason: {0}";

        public String removeMemberSuccess = "Member {0} removed from group {1} successfully.";
        public String removeMemberFailed = "Failed to remove member from group, reason: {0}";

    }

    public GroupProviderHandler(JavaPlugin plugin) {
        instance = this;
    }

    @Override
    public CompletableFuture<GroupDTO> setGroupFlag(@NotNull CommandSender operator,
                                                    @NotNull DominionDTO dominion,
                                                    @NotNull GroupDTO group,
                                                    @NotNull PriFlag flag,
                                                    boolean newValue) {
        GroupSetFlagEvent event = new GroupSetFlagEvent(operator, dominion, group, flag, newValue);
        if (!event.call())
            CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                if (event.getFlag().equals(Flags.ADMIN)) {
                    assertDominionManage(event.getOperator(), event.getDominion());
                } else {
                    assertDominionAdmin(event.getOperator(), event.getDominion());
                }
                assertGroupBelongDominion(event.getGroup(), event.getDominion());
                GroupDTO groupModified;
                if (event.getDominion() instanceof cn.lunadeer.dominion.doos.DominionDOO outpost
                        && outpost.isTownOutpost()
                        && TownManager.isTownManagedGroup(event.getGroup(), event.getDominion())) {
                    groupModified = TownManager.setTownOutpostOverride(outpost, event.getGroup(),
                            event.getFlag(), event.getNewValue());
                } else {
                    groupModified = event.getGroup().setFlagValue(event.getFlag(), event.getNewValue());
                }
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.setFlagSuccess,
                        TownManager.groupDisplayName(groupModified), event.getFlag().getDisplayName(),
                        cn.lunadeer.dominion.misc.DisplayNames.permission(event.getNewValue()));
                return groupModified;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.setFlagFailed, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<GroupDTO> createGroup(@NotNull CommandSender operator,
                                                   @NotNull DominionDTO dominion,
                                                   @NotNull String groupName) {
        GroupCreateEvent event = new GroupCreateEvent(operator, dominion, groupName);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                // Town group creation belongs to the town-wide management page;
                // block creating independent legacy groups from this entry.
                if (event.getDominion() instanceof cn.lunadeer.dominion.doos.DominionDOO doo
                        && doo.isTownOutpost()
                        && event.getOperator() instanceof org.bukkit.entity.Player operatorPlayer
                        && !cn.lunadeer.dominion.misc.Others.bypassLimit(operatorPlayer)) {
                    throw new DominionException(Language.groupProviderHandlerText.townOutpostNoCustomGroups);
                }
                assertGroupName(event.getDominion(), event.getGroupNamePlain());
                GroupDTO group = GroupDOO.create(event.getGroupNameColored(), event.getDominion());
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.createGroupSuccess,
                        event.getGroupNamePlain());
                return group;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.createGroupFailed, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<GroupDTO> deleteGroup(@NotNull CommandSender operator,
                                                   @NotNull DominionDTO dominion,
                                                   @NotNull GroupDTO group) {
        GroupDeleteEvent event = new GroupDeleteEvent(operator, dominion, group);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                assertGroupBelongDominion(event.getGroup(), event.getDominion());
                // the built-in @town group carries town-member default flags
                // (plan §5.1); deleting it would silently strip residents.
                // Only protected while the dominion IS a town outpost —
                // after a release the leftover group is ordinary dead
                // weight the owner may clean up
                if (TownManager.isTownManagedGroup(event.getGroup(), event.getDominion())) {
                    throw new DominionException(event.getGroup().getTownGroupId() == null
                            ? Language.groupProviderHandlerText.townGroupReserved
                            : Language.groupProviderHandlerText.townGroupMirrorReserved);
                }
                GroupDOO.deleteById(event.getGroup().getId());
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.deleteGroupSuccess, event.getGroup().getNamePlain());
                return event.getGroup();
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.deleteGroupFailed, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<GroupDTO> renameGroup(@NotNull CommandSender operator,
                                                   @NotNull DominionDTO dominion,
                                                   @NotNull GroupDTO group,
                                                   @NotNull String newName) {
        GroupRenamedEvent event = new GroupRenamedEvent(operator, dominion, group, newName);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                assertDominionManage(event.getOperator(), event.getDominion());
                assertGroupBelongDominion(event.getGroup(), event.getDominion());
                if (TownManager.isTownManagedGroup(event.getGroup(), event.getDominion())) {
                    throw new DominionException(event.getGroup().getTownGroupId() == null
                            ? Language.groupProviderHandlerText.townGroupReserved
                            : Language.groupProviderHandlerText.townGroupMirrorReserved);
                }
                assertGroupName(event.getDominion(), event.getNewNamePlain());
                GroupDTO renamedGroup = event.getGroup().setName(event.getNewNameColored());
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.renameGroupSuccess,
                        event.getOldNamePlain(), event.getNewNamePlain());
                return renamedGroup;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.renameGroupFailed, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<MemberDTO> addMember(@NotNull CommandSender operator,
                                                  @NotNull DominionDTO dominion,
                                                  @NotNull GroupDTO group,
                                                  @NotNull MemberDTO member) {
        GroupAddMemberEvent event = new GroupAddMemberEvent(operator, dominion, group, member);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                if (TownManager.isTownManagedGroup(event.getGroup(), event.getDominion())) {
                    throw new DominionException(event.getGroup().getTownGroupId() == null
                            ? Language.groupProviderHandlerText.townGroupMembersImplicit
                            : Language.groupProviderHandlerText.townGroupMirrorReserved);
                }
                if (event.getGroup().getFlagValue(Flags.ADMIN) || event.getMember().getFlagValue(Flags.ADMIN)) {
                    assertDominionManage(event.getOperator(), event.getDominion());
                } else {
                    assertDominionAdmin(event.getOperator(), event.getDominion());
                }
                assertMemberBelongDominion(event.getMember(), event.getDominion());
                assertGroupBelongDominion(event.getGroup(), event.getDominion());
                MemberDTO memberModified = ((MemberDOO) event.getMember()).setGroupId(event.getGroup().getId());
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.addMemberSuccess,
                        memberModified.getPlayer().getLastKnownName(), event.getGroup().getNamePlain());
                return memberModified;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.addMemberFailed, e.getMessage());
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<MemberDTO> removeMember(@NotNull CommandSender operator,
                                                     @NotNull DominionDTO dominion,
                                                     @NotNull GroupDTO group,
                                                     @NotNull MemberDTO member) {
        GroupRemoveMemberEvent event = new GroupRemoveMemberEvent(operator, dominion, group, member);
        if (!event.call())
            return CompletableFuture.completedFuture(null);
        return event.getFutureToComplete().completeAsync(() -> {
            try {
                if (TownManager.isTownManagedGroup(event.getGroup(), event.getDominion())) {
                    throw new DominionException(event.getGroup().getTownGroupId() == null
                            ? Language.groupProviderHandlerText.townGroupMembersImplicit
                            : Language.groupProviderHandlerText.townGroupMirrorReserved);
                }
                if (event.getGroup().getFlagValue(Flags.ADMIN)) {
                    assertDominionManage(event.getOperator(), event.getDominion());
                } else {
                    assertDominionAdmin(event.getOperator(), event.getDominion());
                }
                assertGroupBelongDominion(event.getGroup(), event.getDominion());
                assertMemberBelongDominion(event.getMember(), event.getDominion());
                MemberDTO memberModified = ((MemberDOO) event.getMember()).setGroupId(-1);
                Notification.info(event.getOperator(), Language.groupProviderHandlerText.removeMemberSuccess,
                        memberModified.getPlayer().getLastKnownName(), event.getGroup().getNamePlain());
                return memberModified;
            } catch (Exception e) {
                Notification.error(event.getOperator(), Language.groupProviderHandlerText.removeMemberFailed, e.getMessage());
                return null;
            }
        });
    }
}
