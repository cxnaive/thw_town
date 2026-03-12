package cn.lunadeer.dominion.v1_21.events.player.Using;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.utils.XLogger;
import org.bukkit.Material;
import org.bukkit.event.Event; // 导入 Event
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

        // 2. 检查：是否已经被其他插件显式禁止了“使用物品”
        // 如果别人已经 DENY 了，我们就不需要再处理了 (根据你的逻辑决定是否保留)
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        
        // 3. 检查：如果是右键方块且已经被取消了，则跳过
        // (空气交互通常自带 Cancelled 状态，所以不能在这里无脑跳过)
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
        // 我们传入 event 是为了让它帮忙发消息或做通用处理
        // 但控制权我们要拿在手里
        boolean allowed = checkPrivilegeFlag(event.getPlayer().getLocation(), Flags.USE_MAP, event.getPlayer(), event);

        // 如果没有权限 (allowed == false)
        if (!allowed) {
            
            // 调试日志
            // XLogger.info("Blocking map usage for {0}", event.getPlayer().getName());
            
            // 【关键步骤 1】显式禁止物品使用
            // 无论 checkPrivilegeFlag 内部是否调用了 setCancelled(true)，
            // 这行代码专门针对"右键空气使用地图"生效。
            event.setUseItemInHand(Event.Result.DENY);
            
            // 【关键步骤 2】为了保险，把通用取消也加上 (如果内部没加的话)
            event.setCancelled(true);

            // 【关键步骤 3】解决客户端预测导致的视觉 Bug
            // 必须加，否则玩家手里会显示一张假地图
            event.getPlayer().updateInventory();
        }
    }
}
