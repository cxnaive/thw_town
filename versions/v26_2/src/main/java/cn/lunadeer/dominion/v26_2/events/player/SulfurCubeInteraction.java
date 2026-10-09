package cn.lunadeer.dominion.v26_2.events.player;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.events.LowestVersion;
import cn.lunadeer.dominion.events.PaperOnly;
import cn.lunadeer.dominion.utils.XVersionManager;
import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;

import static cn.lunadeer.dominion.misc.Others.checkPrivilegeFlag;

@PaperOnly
@LowestVersion(XVersionManager.ImplementationVersion.v26_2)
public class SulfurCubeInteraction implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void swallow(SulfurCubeSwallowItemEvent event) {
        // The cube stores a block; replacing it also releases the old block.
        checkPrivilegeFlag(event.getEntity().getLocation(), Flags.CONTAINER, event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void capture(PlayerBucketEntityEvent event) {
        if (event.getEntity() instanceof SulfurCube) {
            checkPrivilegeFlag(event.getEntity().getLocation(), Flags.BREAK_BLOCK, event.getPlayer(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof SulfurCube && event.getDamager() instanceof Player player) {
            checkPrivilegeFlag(event.getEntity().getLocation(), Flags.ANIMAL_KILLING, player, event);
        }
    }
}
