package cn.lunadeer.dominion.v1_20_1.events.player.Using;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.utils.XLogger;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import static cn.lunadeer.dominion.misc.Others.checkPrivilegeFlag;

public class UseMap implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {

        // 1. 过滤动作：只处理右键
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 2. 检查：是否已经被其他插件显式禁止了"使用物品"
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }

        // 3. 检查：如果是右键方块且已经被取消了，则跳过
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.isCancelled()) {
            return;
        }

        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack item = null;
        switch (event.getHand()) {
            case HAND -> item = inventory.getItemInMainHand();
            case OFF_HAND -> item = inventory.getItemInOffHand();
        }

        if (item == null || item.getType() != Material.MAP) {
            return;
        }

        // 4. 【核心逻辑】直接使用 checkPrivilegeFlag 的返回值
        boolean allowed = checkPrivilegeFlag(event.getPlayer().getLocation(), Flags.USE_MAP, event.getPlayer(), event);

        // 如果没有权限 (allowed == false)
        if (!allowed) {

            // 调试日志
            // XLogger.info("Blocking map usage for {0}", event.getPlayer().getName());

            // 【关键步骤 1】显式禁止物品使用
            event.setUseItemInHand(Event.Result.DENY);

            // 【关键步骤 2】通用取消
            event.setCancelled(true);

            // 【关键步骤 3】解决客户端预测导致的视觉 Bug
            event.getPlayer().updateInventory();
        }
    }
}
