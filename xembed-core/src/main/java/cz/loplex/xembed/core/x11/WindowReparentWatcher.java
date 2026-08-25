package cz.loplex.xembed.core.x11;

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
        X11Ext.INSTANCE.XSelectInput(display.raw(), new Window(windowId), new NativeLong(X11Ext.StructureNotifyMask));
        X11Ext.INSTANCE.XFlush(display.raw());
    }

    public void unwatch(long windowId) {
        callbacks.remove(windowId);
    }

    private void loop() {
        XEvent event = new XEvent();
        while (running) {
            if (X11Ext.INSTANCE.XCheckTypedEvent(display.raw(), X11Ext.ReparentNotify, event)) {
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
