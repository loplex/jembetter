package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Watches a window's {@code GetParent()} for changes and invokes a callback
 * with the new parent (0 if the window was destroyed) each time it changes —
 * the Win32 stand-in for {@code jembetter-core}'s X11 {@code
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

    private static final long POLL_INTERVAL_MILLIS = 50;

    private final Thread thread;
    private final Map<Long, LongConsumer> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastKnownParent = new ConcurrentHashMap<>();
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
        lastKnownParent.put(hwnd, currentParentOf(hwnd));
        callbacks.put(hwnd, onReparented);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
        lastKnownParent.remove(hwnd);
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
        long current = currentParentOf(hwnd);
        Long previous = lastKnownParent.put(hwnd, current);
        if (previous != null && previous != current) {
            try {
                callback.accept(current);
            } catch (RuntimeException e) {
                // A misbehaving callback must not take the watcher thread down.
                e.printStackTrace();
            }
        }
    }

    private static long currentParentOf(long hwnd) {
        HWND handle = new HWND(new Pointer(hwnd));
        if (!User32.INSTANCE.IsWindow(handle)) {
            return 0;
        }
        HWND parent = User32.INSTANCE.GetParent(handle);
        return parent == null ? 0 : Pointer.nativeValue(parent.getPointer());
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
