package cn.lunadeer.dominion.managers.teleport;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** State and resource ownership survive callbacks arriving after cancellation. */
final class TeleportRequest {
    enum State { WAITING, PREPARING, HANDOFF, EXECUTING, SUCCEEDED, FAILED, CANCELLED }

    final UUID id;
    final UUID session;
    final int dominionId;
    final boolean internal;
    final Origin origin;
    volatile TeleportTarget target;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private AutoCloseable resource;
    private final List<Runnable> cleanup = new ArrayList<>();

    record Origin(UUID world, int x, int y, int z) {
        boolean matches(UUID world, int x, int y, int z) {
            return this.world.equals(world) && this.x == x && this.y == y && this.z == z;
        }
    }

    TeleportRequest(UUID id, UUID session, int dominionId, boolean internal, Origin origin) {
        this.id = id;
        this.session = session;
        this.dominionId = dominionId;
        this.internal = internal;
        this.origin = origin;
    }

    State state() { return state.get(); }
    boolean transition(State from, State to) { return state.compareAndSet(from, to); }
    boolean preparing() { return state() == State.PREPARING; }
    boolean terminal() { return terminal(state()); }
    private static boolean terminal(State value) {
        return value == State.SUCCEEDED || value == State.FAILED || value == State.CANCELLED;
    }

    synchronized void resource(AutoCloseable value) {
        if (terminal()) close(value);
        else {
            close(resource);
            resource = value;
        }
    }

    synchronized void cleanup(Runnable action) {
        if (terminal()) action.run();
        else cleanup.add(action);
    }

    synchronized boolean finish(State end, boolean sessionEnded) {
        if (!terminal(end)) throw new IllegalArgumentException("Not a terminal state");
        State previous;
        do {
            previous = state();
            if (terminal(previous)) return false;
            if (end == State.CANCELLED && previous == State.EXECUTING && !sessionEnded) return false;
        } while (!state.compareAndSet(previous, end));
        close(resource);
        resource = null;
        cleanup.forEach(Runnable::run);
        cleanup.clear();
        return true;
    }

    private static void close(AutoCloseable value) {
        if (value == null) return;
        try { value.close(); }
        catch (Exception exception) { throw new IllegalStateException("Cannot release teleport resource", exception); }
    }
}
