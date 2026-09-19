package cn.lunadeer.dominion.managers;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.managers.teleport.TeleportService;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Compatibility facade shared by commands, town/legacy GUI and TUI. */
public class TeleportManager implements Listener {
    private static TeleportService service;

    public static class TeleportManagerText extends ConfigurationPart {
        public String coolingDown = "Please wait for {0} seconds before teleporting again.";
        public String disabled = "Teleportation is disabled for your permission group.";
        public String delay = "Will teleport in {0} seconds, don't move...";
        public String unfinishedCancelled = "Cancelled previous unfinished teleportation.";
        public String cancelMove = "Cancelled teleportation due to movement or another teleport.";
        public String multiServerDisabled = "Cross-server teleportation is disabled on this server.";
        public String busy = "A teleport is already in progress. Please wait.";
        public String preparing = "Preparing a safe destination...";
        public String connecting = "Connecting to the destination server...";
        public String success = "Teleport completed.";
        public String airborneSuccess = "Teleport completed at an airborne position. Be prepared to fall.";
        public String targetMissing = "The destination dominion no longer exists.";
        public String targetChanged = "The destination changed. Please try again.";
        public String worldUnavailable = "The destination world is not loaded. Please contact an administrator.";
        public String permissionDenied = "You do not have permission to teleport to this dominion.";
        public String noSafeLocation = "No safe landing point was found. Ask a manager to set the dominion teleport point.";
        public String timedOut = "Preparing the destination timed out. You have not been moved; please try again.";
        public String rejected = "The server or another plugin refused the teleport.";
        public String failed = "Teleport failed. Please try again or contact an administrator.";
        public String unconfirmed = "The server has not confirmed the teleport result yet. Please wait before trying again.";
    }

    public TeleportManager(JavaPlugin plugin) {
        service = new TeleportService(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static void shutdown() {
        if (service != null) service.close();
    }

    public static void teleportToDominion(Player player, DominionDTO dominion) {
        service.teleport(player, dominion.getId());
    }

    /** Internal protection entry: no player TP permission, countdown or cooldown. */
    public static void eject(Player player, Location location, DominionDTO excluded) {
        service.eject(player, location, excluded);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { service.joined(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { service.quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        service.moved(event.getPlayer(), event.getFrom(), event.getTo(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        service.moved(event.getPlayer(), event.getFrom(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Location current = event.getPlayer().getLocation();
        service.moved(event.getPlayer(), new Location(event.getFrom(), 0, 0, 0), current, true);
    }
}
