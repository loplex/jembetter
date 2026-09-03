package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import cz.loplex.jembetter.common.SizeListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires a callback with a watched window's new width/height whenever it
 * changes — the Win32 stand-in for {@code jembetter-core-x11}'s {@code
 * WindowConfigureWatcher}, which relies on a real {@code ConfigureNotify}
 * event.
 *
 * <p><b>Poll-based, the same way {@link Win32ReparentWatcher}/{@link
 * Win32FocusWatcher} are, and for the same underlying reason:</b> an embedded
 * child HWND's own {@code WM_SIZE}/{@code WM_WINDOWPOSCHANGED} are delivered
 * only inside that window's own message loop, invisible cross-process. This
 * class instead polls {@code GetClientRect} on each watched window — local,
 * decoration-free coordinates, matching what {@code ConfigureNotify}'s own
 * width/height report on the X11 side, and what {@code Win32EmbedCore}'s
 * {@code MoveWindow}-based resize sets on an embedded, undecorated child.
 * Only genuine transitions are reported, the same deduplication the other two
 * watchers do.
 */
public final class Win32ConfigureWatcher implements AutoCloseable {

    private static final long POLL_INTERVAL_MILLIS = 50;

    private final Thread thread;
    private final Map<Long, SizeListener> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, long[]> lastKnownSize = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public Win32ConfigureWatcher() {
        this.thread = new Thread(this::loop, "jembetter-win32-configure-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts watching {@code hwnd}; {@code onResized} runs on the watcher's own thread with its new width/height, until {@link #unwatch} or {@link #close}. */
    public void watch(long hwnd, SizeListener onResized) {
        lastKnownSize.put(hwnd, currentSizeOf(hwnd));
        callbacks.put(hwnd, onResized);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
        lastKnownSize.remove(hwnd);
    }

    private void loop() {
        while (running) {
            for (Map.Entry<Long, SizeListener> entry : callbacks.entrySet()) {
                pollOne(entry.getKey(), entry.getValue());
            }
            idle();
        }
    }

    private void pollOne(long hwnd, SizeListener callback) {
        long[] current = currentSizeOf(hwnd);
        long[] previous = lastKnownSize.put(hwnd, current);
        if (previous != null && (previous[0] != current[0] || previous[1] != current[1])) {
            try {
                callback.resized((int) current[0], (int) current[1]);
            } catch (RuntimeException e) {
                // A misbehaving callback must not take the watcher thread down.
                e.printStackTrace();
            }
        }
    }

    private static long[] currentSizeOf(long hwnd) {
        HWND handle = new HWND(new Pointer(hwnd));
        RECT rect = new RECT();
        if (!User32.INSTANCE.IsWindow(handle) || !User32.INSTANCE.GetClientRect(handle, rect)) {
            return new long[] { 0, 0 };
        }
        return new long[] { rect.right - rect.left, rect.bottom - rect.top };
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
