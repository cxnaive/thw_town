package cn.lunadeer.dominion.utils;

import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Double-click confirmation utility for destructive town operations.
 * First click stores the action and warns; second click within timeout executes.
 */
public class PendingConfirmation {
    private static final Map<ConfirmKey, PendingAction> pending = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 5000; // 5 seconds

    record ConfirmKey(UUID player, String actionId) {}
    record PendingAction(Runnable action, long expireAt) {}

    /**
     * Request confirmation for a destructive action.
     * 
     * @param player the player performing the action
     * @param actionId unique identifier for this action (e.g., "kick:townId:targetUUID")
     * @param action the action to execute on confirmation
     * @param warningMsg the warning message to show on first click
     * @return true if the action was executed (second click), false if pending (first click)
     */
    public static boolean requestConfirm(Player player, String actionId, Runnable action, String warningMsg) {
        ConfirmKey key = new ConfirmKey(player.getUniqueId(), actionId);
        long now = System.currentTimeMillis();
        
        PendingAction pa = pending.get(key);
        if (pa != null && now < pa.expireAt) {
            // Second click within timeout — execute
            pending.remove(key);
            action.run();
            return true;
        }
        
        // First click — store and warn
        pending.put(key, new PendingAction(action, now + TIMEOUT_MS));
        Notification.warn(player, warningMsg);
        
        // Schedule cleanup after timeout
        Scheduler.runTaskLater(() -> pending.remove(key), TIMEOUT_MS / 50); // 100 ticks (5 seconds)
        return false;
    }
}
