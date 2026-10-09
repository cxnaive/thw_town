package cn.lunadeer.dominion.v26_2.events.environment;

import cn.lunadeer.dominion.api.dtos.flag.Flags;
import cn.lunadeer.dominion.events.LowestVersion;
import cn.lunadeer.dominion.utils.XVersionManager;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;

import static cn.lunadeer.dominion.misc.Others.checkEnvironmentFlag;

@LowestVersion(XVersionManager.ImplementationVersion.v26_2)
public class SulfurCubeExplosion implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void blocks(EntityExplodeEvent event) {
        if (event.getEntity() instanceof SulfurCube) {
            event.blockList().removeIf(block -> !checkEnvironmentFlag(block.getLocation(), Flags.TNT_EXPLODE, null));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof SulfurCube && event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            checkEnvironmentFlag(event.getEntity().getLocation(), Flags.TNT_EXPLODE, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void hanging(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof SulfurCube) {
            checkEnvironmentFlag(event.getEntity().getLocation(), Flags.TNT_EXPLODE, event);
        }
    }
}
