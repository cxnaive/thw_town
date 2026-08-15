package cn.lunadeer.dominion.handler;

import cn.lunadeer.dominion.managers.TownLevelManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Delivers item refunds that were queued while a player was offline. */
public final class TownLevelRefundHandler implements Listener {

    public TownLevelRefundHandler(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        TownLevelManager.retryPendingRefunds(event.getPlayer());
    }
}
