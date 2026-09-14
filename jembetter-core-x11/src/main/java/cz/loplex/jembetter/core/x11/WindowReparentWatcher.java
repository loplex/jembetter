package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XEvent;
import com.sun.jna.platform.unix.X11.XReparentEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Watches windows for {@code ReparentNotify} and invokes a callback with the
 * window's new parent each time one is reparented — the mechanism behind
 * client-side host-death detection: a window added to its embedder's
 * save-set (see {@link Reparenting#reparent}) is reparented back to the
 * root window by the X server, and mapped, as soon as the embedder's
 * connection closes for any reason, with no XEmbed message involved.
 *
 * <p>Runs a background thread on its own X11 connection, since Xlib
 * connections aren't safe to share across threads without XInitThreads.
 */
public final class WindowReparentWatcher implements AutoCloseable {

    private final X11Display display;
    private final Thread thread;
    private final Map<Long, LongConsumer> callbacks = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WindowReparentWatcher() {
        this.display = X11Display.open(null);
        this.thread = new Thread(this::loop, "xembed-window-reparent-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts watching {@code windowId}; {@code onReparented} runs on the watcher's own thread with the window's new parent id. */
    public void watch(long windowId, LongConsumer onReparented) {
        callbacks.put(windowId, onReparented);
        display.ifOpen(raw -> {
            X11Ext.INSTANCE.XSelectInput(raw, new Window(windowId),
                    new NativeLong(X11Ext.StructureNotifyMask));
            // XSync, not XFlush: XFlush only empties the output buffer, so
            // watch() could return before the server had processed the
            // XSelectInput above. Each watcher owns a separate connection from
            // its caller's, so the caller's very next request - destroying or
            // reparenting this window - could reach the server first, and
            // XSelectInput is not retroactive: the ReparentNotify it was
            // registered for would simply never be sent. XSync returns only
            // once the server has processed the request, which is what callers
            // already assume watch() guarantees. It costs one round trip, held
            // under GLOBAL_LOCK, on a path that runs once per watched window.
            X11Ext.INSTANCE.XSync(raw, false);
        });
    }

    public void unwatch(long windowId) {
        callbacks.remove(windowId);
    }

    private void loop() {
        XEvent event = new XEvent();
        while (running) {
            boolean pending = display.ifOpen(
                    raw -> X11Ext.INSTANCE.XCheckTypedEvent(raw, X11Ext.ReparentNotify, event), false);
            if (pending) {
                dispatch(event);
            } else {
                idle();
            }
        }
    }

    private void dispatch(XEvent event) {
        event.setType(XReparentEvent.class);
        event.read();
        long windowId = event.xreparent.window.longValue();
        long newParentId = event.xreparent.parent.longValue();
        LongConsumer callback = callbacks.get(windowId);
        if (callback != null) {
            try {
                callback.accept(newParentId);
            } catch (RuntimeException e) {
                // A misbehaving callback must not take the watcher thread down.
                e.printStackTrace(System.err);
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

    /**
     * Stops the background thread and closes this watcher's connection.
     *
     * <p>The join is deliberately bounded: a callback this watcher invoked
     * can block for as long as it likes, and a watcher must not hold its
     * owner's teardown hostage. So the event loop can still be running when
     * the connection is freed here, which is why every native call it makes
     * goes through {@link X11Display#ifOpen(java.util.function.Function,
     * Object)} rather than taking {@code GLOBAL_LOCK} directly — see {@link
     * X11Display} for what happens otherwise.
     */
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
