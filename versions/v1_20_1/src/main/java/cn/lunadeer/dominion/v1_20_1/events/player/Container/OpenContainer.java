package cn.lunadeer.dominion.v1_20_1.events.player.Container;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

import static cn.lunadeer.dominion.misc.Others.checkPrivilegeFlag;

/** Check the actual inventory, including both halves of a chest across a claim boundary. */
public class OpenContainer implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void handler(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        switch (event.getInventory().getType()) {
            case CHEST, BARREL, SHULKER_BOX -> {
                // Plugin menus and player inventories have no world location.
                if (event.getInventory().getLocation() == null) return;
                InventoryHolder holder = event.getInventory().getHolder();
                if (holder instanceof DoubleChest chest) {
                    checkSide(chest.getLeftSide(), player, event);
                    if (!event.isCancelled()) checkSide(chest.getRightSide(), player, event);
                } else {
                    checkPrivilegeFlag(event.getInventory().getLocation(), Flags.CONTAINER, player, event);
                }
            }
            default -> { }
        }
    }

    private void checkSide(InventoryHolder holder, Player player, InventoryOpenEvent event) {
        if (holder instanceof BlockState block) {
            checkPrivilegeFlag(block.getLocation(), Flags.CONTAINER, player, event);
        }
    }
}
