package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Watches a window's {@code GetParent()} for changes and invokes a callback
 * with the new parent (0 if the window was destroyed) each time it changes —
 * the Win32 stand-in for {@code jembetter-core-x11}'s {@code
 * WindowReparentWatcher}, which relies on a real {@code ReparentNotify}
 * event. Win32 has no equivalent event a caller outside the window's own
 * thread can subscribe to ({@code WM_PARENTNOTIFY} only reaches the
 * <em>parent's</em> own message loop) and no save-set mechanism that
 * automatically reparents a child back to the desktop when the window that
 * reparented it goes away — destroying a parent HWND destroys its children
 * outright instead.
 *
 * <p><b>Poll-based by necessity, and verified on a real Windows machine</b>
 * (2026-08-28 follow-up spike, see this module's package-info): watching the
 * embedded client window across an externally-triggered embed, a host detach
 * back to the desktop, and the host frame being destroyed, this class fired
 * its callback with the right new parent (and with 0 when the parent-destroy
 * took the child with it) for all three. The 50 ms poll does mean a
 * transition and its immediate reversal within one interval can be missed
 * entirely — not a problem for the embed/detach lifecycle {@code
 * EmbedPlugWin32} uses it for, which doesn't flip that fast.
 */
public final class Win32ReparentWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Win32ReparentWatcher.class);

    private static final long POLL_INTERVAL_MILLIS = 50;

    private final Thread thread;
    private final Map<Long, LongConsumer> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, Runnable> destroyCallbacks = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastKnownParent = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lastKnownAlive = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public Win32ReparentWatcher() {
        this.thread = new Thread(this::loop, "xembed-win32-reparent-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Starts watching {@code hwnd}; {@code onReparented} runs on the
     * watcher's own thread with the window's new parent (0 for "no parent",
     * whether because it was released to the desktop or because the window
     * itself no longer exists) whenever a poll observes a change from the
     * previously observed parent.
     */
    public void watch(long hwnd, LongConsumer onReparented) {
        watch(hwnd, onReparented, () -> {
        });
    }

    /**
     * Same as {@link #watch(long, LongConsumer)}, plus {@code onDestroyed}
     * for the window ceasing to exist.
     *
     * <p>Worth having separately because {@code onReparented} cannot express
     * it: its argument is 0 both for "this window has no parent" and for
     * "this window is gone", so a window that is embedded and then destroyed
     * with its parent goes 0 -> host -> 0. If no poll lands while the host
     * owns it — 50 ms is a long time for a host to embed a client and then
     * crash — the watcher sees the same value twice, reports nothing at all,
     * and the destruction passes unnoticed. {@code onDestroyed} fires on the
     * transition itself, so it does not depend on the parent having been
     * observed to change.
     *
     * <p>Both fire when a window with a parent is destroyed: {@code
     * onReparented(0)} first, then {@code onDestroyed}. A caller that acts on
     * either must tolerate the other following it.
     */
    public void watch(long hwnd, LongConsumer onReparented, Runnable onDestroyed) {
        lastKnownParent.put(hwnd, currentParentOf(hwnd));
        lastKnownAlive.put(hwnd, isWindow(hwnd));
        callbacks.put(hwnd, onReparented);
        destroyCallbacks.put(hwnd, onDestroyed);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
        destroyCallbacks.remove(hwnd);
        lastKnownParent.remove(hwnd);
        lastKnownAlive.remove(hwnd);
    }

    private void loop() {
        while (running) {
            for (Map.Entry<Long, LongConsumer> entry : callbacks.entrySet()) {
                pollOne(entry.getKey(), entry.getValue());
            }
            idle();
        }
    }

    private void pollOne(long hwnd, LongConsumer callback) {
        boolean alive = isWindow(hwnd);
        long current = currentParentOf(hwnd);
        Long previous = lastKnownParent.put(hwnd, current);
        Boolean wasAlive = lastKnownAlive.put(hwnd, alive);

        if (previous != null && previous != current) {
            dispatch(() -> callback.accept(current));
        }
        if (Boolean.TRUE.equals(wasAlive) && !alive) {
            Runnable onDestroyed = destroyCallbacks.get(hwnd);
            if (onDestroyed != null) {
                dispatch(onDestroyed);
            }
        }
    }

    private static void dispatch(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            // A misbehaving callback must not take the watcher thread down.
            LOG.warn("A window-reparented callback threw", e);
        }
    }

    private static boolean isWindow(long hwnd) {
        return User32.INSTANCE.IsWindow(new HWND(new Pointer(hwnd)));
    }

    /**
     * Deliberately {@link Win32Reparent#parentOf} rather than a second
     * {@code GetParent} of its own: what counts as "has a parent" wants one
     * definition, and that one already excludes the desktop — which this
     * watcher would otherwise report as a reparent every time it polled a
     * window mid-{@code SetParent}. See there.
     */
    private static long currentParentOf(long hwnd) {
        HWND handle = new HWND(new Pointer(hwnd));
        if (!User32.INSTANCE.IsWindow(handle)) {
            return 0;
        }
        return Win32Reparent.parentOf(hwnd);
    }

    private void idle() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
