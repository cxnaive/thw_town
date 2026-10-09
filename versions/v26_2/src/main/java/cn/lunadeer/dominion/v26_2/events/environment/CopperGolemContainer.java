package cn.lunadeer.dominion.v26_2.events.environment;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.cache.CacheManager;
import cn.lunadeer.dominion.events.LowestVersion;
import cn.lunadeer.dominion.events.PaperOnly;
import cn.lunadeer.dominion.utils.XVersionManager;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.InventoryHolder;

import static cn.lunadeer.dominion.misc.Others.checkEnvironmentFlag;

@PaperOnly
@LowestVersion(XVersionManager.ImplementationVersion.v26_2)
public class CopperGolemContainer implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void handler(ItemTransportingEntityValidateTargetEvent event) {
        if (!event.isAllowed() || !(event.getEntity() instanceof CopperGolem)) return;
        Location golem = event.getEntity().getLocation();
        BlockState state = event.getBlock().getState();
        if (state instanceof InventoryHolder holder && holder.getInventory().getHolder() instanceof DoubleChest chest) {
            if (!allowed(golem, ((BlockState) chest.getLeftSide()).getLocation())
                    || !allowed(golem, ((BlockState) chest.getRightSide()).getLocation())) event.setAllowed(false);
        } else if (!allowed(golem, event.getBlock().getLocation())) {
            event.setAllowed(false);
        }
    }

    private boolean allowed(Location golem, Location container) {
        DominionDTO source = CacheManager.instance.getDominion(container);
        if (source == null) return true;
        DominionDTO transporter = CacheManager.instance.getDominion(golem);
        if (transporter != null && source.getId().equals(transporter.getId())) return true;
        // Golems do not fire InventoryMoveItemEvent. Apply the existing boundary flag
        // while their AI validates a chest, before it can remove any items.
        return checkEnvironmentFlag(container, Flags.HOPPER_OUTSIDE, null);
    }
}
