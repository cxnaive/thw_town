package cn.lunadeer.dominion.handler;

import cn.lunadeer.dominion.configuration.Configuration;
import cn.lunadeer.dominion.managers.TownInviteManager;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/** Delivers shared town invitations on login and by periodic database poll. */
public final class TownInviteNotificationHandler implements Listener {

    public TownInviteNotificationHandler(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        int interval = Configuration.town.inviteNotificationIntervalSeconds;
        if (interval > 0) {
            long ticks = interval * 20L;
            Scheduler.runTaskRepeat(this::pollOnlinePlayers, ticks, ticks);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        TownInviteManager.notifyPendingAsync(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        TownInviteManager.clearPlayer(event.getPlayer().getUniqueId());
    }

    private void pollOnlinePlayers() {
        List<UUID> players = Bukkit.getOnlinePlayers().stream()
                .map(player -> player.getUniqueId())
                .toList();
        TownInviteManager.notifyPendingAsync(players);
    }
}
