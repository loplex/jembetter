package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XConfigureEvent;
import com.sun.jna.platform.unix.X11.XEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches windows for {@code ConfigureNotify} and invokes a callback with the
 * window's new width/height each time one is resized — the mechanism behind
 * client-side resize notification for a toolkit-opaque embed ({@code
 * cz.loplex.jembetter.host.EmbedSocket#embedOpaque}): unlike XEmbed's own
 * ClientMessages, {@code ConfigureNotify} is a real server-generated event
 * that a window's own creator receives regardless of which other connection
 * resized it (here, the host's {@code WindowGeometry#moveResize} following a
 * host-side resize into the embedded window) — the same property {@link
 * WindowReparentWatcher} already relies on for host-death detection.
 *
 * <p>Runs a background thread on its own X11 connection, since Xlib
 * connections aren't safe to share across threads without XInitThreads.
 */
public final class WindowConfigureWatcher implements AutoCloseable {

    private final X11Display display;
    private final Thread thread;
    private final Map<Long, SizeListener> callbacks = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WindowConfigureWatcher() {
        this.display = X11Display.open(null);
        this.thread = new Thread(this::loop, "xembed-window-configure-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts watching {@code windowId}; {@code onResized} runs on the watcher's own thread with the window's new width/height. */
    public void watch(long windowId, SizeListener onResized) {
        callbacks.put(windowId, onResized);
        X11Ext.INSTANCE.XSelectInput(display.raw(), new Window(windowId), new NativeLong(X11Ext.StructureNotifyMask));
        X11Ext.INSTANCE.XFlush(display.raw());
    }

    public void unwatch(long windowId) {
        callbacks.remove(windowId);
    }

    private void loop() {
        XEvent event = new XEvent();
        while (running) {
            if (X11Ext.INSTANCE.XCheckTypedEvent(display.raw(), X11Ext.ConfigureNotify, event)) {
                dispatch(event);
            } else {
                idle();
            }
        }
    }

    private void dispatch(XEvent event) {
        event.setType(XConfigureEvent.class);
        event.read();
        long windowId = event.xconfigure.window.longValue();
        SizeListener callback = callbacks.get(windowId);
        if (callback != null) {
            try {
                callback.resized(event.xconfigure.width, event.xconfigure.height);
            } catch (RuntimeException e) {
                // A misbehaving callback must not take the watcher thread down.
                e.printStackTrace();
            }
        }
    }

    private void idle() {
        try {
            Thread.sleep(20);
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
        display.close();
    }
}
