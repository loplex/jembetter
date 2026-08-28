package cz.loplex.xembed.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XDestroyWindowEvent;
import com.sun.jna.platform.unix.X11.XEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/**
 * Watches windows for {@code DestroyNotify} and invokes a callback when one
 * is destroyed — the reliable way to detect that an embedded window's
 * process has exited or crashed: the X server generates DestroyNotify for
 * all of a client's windows as soon as it notices that client's connection
 * is gone, regardless of whether the process exited cleanly.
 *
 * <p>Runs a background thread on its own X11 connection, since Xlib
 * connections aren't safe to share across threads without XInitThreads.
 */
public final class WindowDeathWatcher implements AutoCloseable {

    private final X11Display display;
    private final Thread thread;
    private final Map<Long, LongConsumer> callbacks = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WindowDeathWatcher() {
        this.display = X11Display.open(null);
        this.thread = new Thread(this::loop, "xembed-window-death-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts watching {@code windowId}; {@code onDestroyed} runs on the watcher's own thread. */
    public void watch(long windowId, LongConsumer onDestroyed) {
        callbacks.put(windowId, onDestroyed);
        X11Ext.INSTANCE.XSelectInput(display.raw(), new Window(windowId), new NativeLong(X11Ext.StructureNotifyMask));
        X11Ext.INSTANCE.XFlush(display.raw());
    }

    public void unwatch(long windowId) {
        callbacks.remove(windowId);
    }

    private void loop() {
        XEvent event = new XEvent();
        while (running) {
            if (X11Ext.INSTANCE.XCheckTypedEvent(display.raw(), X11Ext.DestroyNotify, event)) {
                dispatch(event);
            } else {
                idle();
            }
        }
    }

    private void dispatch(XEvent event) {
        event.setType(XDestroyWindowEvent.class);
        event.read();
        long windowId = event.xdestroywindow.window.longValue();
        LongConsumer callback = callbacks.remove(windowId);
        if (callback != null) {
            try {
                callback.accept(windowId);
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
