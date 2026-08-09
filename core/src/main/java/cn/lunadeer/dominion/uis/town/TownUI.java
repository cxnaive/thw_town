package cn.lunadeer.dominion.uis.town;

import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.scheduler.Scheduler;
import org.bukkit.entity.Player;

/**
 * Thread discipline for the town UI (plan §13.5): TownManager mutations
 * and cross-server outpost queries run real database I/O, so they must
 * not execute on the main thread (inventory clicks and TUI buttons fire
 * there). Actions hop async and re-render on the main thread; pages with
 * DB-backed content prefetch async, then render sync.
 */
public final class TownUI {

    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Run a mutation off the main thread, then re-render sync. */
    public static void action(Player player, ThrowingRunnable action, Runnable reshow) {
        Scheduler.runTaskAsync(() -> {
            try {
                action.run();
            } catch (Exception e) {
                Notification.error(player, e);
                return;
            }
            if (reshow != null) {
                Scheduler.runTask(reshow);
            }
        });
    }

    /** Prefetch DB data off the main thread, then render sync. */
    public static void prefetchThenRender(Player player, ThrowingRunnable prefetch, Runnable render) {
        Scheduler.runTaskAsync(() -> {
            try {
                prefetch.run();
            } catch (Exception e) {
                Notification.error(player, e);
                return;
            }
            Scheduler.runTask(render);
        });
    }

    private TownUI() {
    }
}
