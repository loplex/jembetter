package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XEvent;
import com.sun.jna.platform.unix.X11.XFocusChangeEvent;
import cz.loplex.jembetter.common.FocusListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches windows for {@code FocusIn}/{@code FocusOut} and invokes a callback
 * each time one gains or loses X input focus — the mechanism behind
 * client-side focus notification, the counterpart of XEmbed's host&rarr;client
 * {@code XEMBED_FOCUS_IN}/{@code XEMBED_FOCUS_OUT} ClientMessages.
 *
 * <p>Those ClientMessages are undeliverable to a client whose top-level
 * window was created by a different connection than the one reading events
 * for it (AWT's own, not this library's — see {@link
 * cz.loplex.jembetter.core.xembed.XEmbedInboundWatcher}'s Javadoc). But a
 * host granting the embedded client focus does so with {@code
 * XSetInputFocus} ({@link InputFocus#set}), which generates a real,
 * server-side {@code FocusIn} on the client window — and a real {@code
 * FocusOut} when focus later moves away — that any connection selecting
 * {@code FocusChangeMask} on that window receives, regardless of which
 * connection owns it. Same property {@link WindowReparentWatcher} and {@link
 * WindowConfigureWatcher} already rely on.
 *
 * <p>Only genuine focus transitions are reported: {@code FocusIn}/{@code
 * FocusOut} pairs caused by keyboard/pointer <em>grabs</em> (mode {@code
 * NotifyGrab}/{@code NotifyUngrab}) — e.g. the passive {@code XGrabButton}
 * behind click-to-focus — and pointer crossings (detail {@code
 * NotifyPointer}) are filtered out, and a transition to a state already
 * reported for that window is suppressed, so a caller sees one {@code
 * focusChanged(true)} per actual focus gain and one {@code
 * focusChanged(false)} per actual loss.
 *
 * <p>Runs a background thread on its own X11 connection, since Xlib
 * connections aren't safe to share across threads without XInitThreads.
 */
public final class WindowFocusWatcher implements AutoCloseable {

    private final X11Display display;
    private final Thread thread;
    private final Map<Long, FocusListener> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lastReported = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public WindowFocusWatcher() {
        this.display = X11Display.open(null);
        this.thread = new Thread(this::loop, "xembed-window-focus-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts watching {@code windowId}; {@code onFocusChanged} runs on the watcher's own thread with the new focus state. */
    public void watch(long windowId, FocusListener onFocusChanged) {
        callbacks.put(windowId, onFocusChanged);
        synchronized (X11Display.GLOBAL_LOCK) {
            X11Ext.INSTANCE.XSelectInput(display.raw(), new Window(windowId), new NativeLong(X11Ext.FocusChangeMask));
            X11Ext.INSTANCE.XFlush(display.raw());
        }
    }

    public void unwatch(long windowId) {
        callbacks.remove(windowId);
        lastReported.remove(windowId);
    }

    private void loop() {
        XEvent event = new XEvent();
        while (running) {
            boolean gotFocusIn;
            boolean gotFocusOut = false;
            synchronized (X11Display.GLOBAL_LOCK) {
                gotFocusIn = X11Ext.INSTANCE.XCheckTypedEvent(display.raw(), X11Ext.FocusIn, event);
                if (!gotFocusIn) {
                    gotFocusOut = X11Ext.INSTANCE.XCheckTypedEvent(display.raw(), X11Ext.FocusOut, event);
                }
            }
            if (gotFocusIn) {
                dispatch(event, true);
            } else if (gotFocusOut) {
                dispatch(event, false);
            } else {
                idle();
            }
        }
    }

    private void dispatch(XEvent event, boolean focused) {
        event.setType(XFocusChangeEvent.class);
        event.read();
        if (isGrabArtefact(event.xfocus.mode) || event.xfocus.detail == X11Ext.NotifyPointer) {
            return;
        }
        long windowId = event.xfocus.window.longValue();
        FocusListener callback = callbacks.get(windowId);
        if (callback == null) {
            return;
        }
        Boolean previous = lastReported.put(windowId, focused);
        if (previous != null && previous == focused) {
            return;
        }
        try {
            callback.focusChanged(focused);
        } catch (RuntimeException e) {
            // A misbehaving callback must not take the watcher thread down.
            e.printStackTrace();
        }
    }

    private static boolean isGrabArtefact(int mode) {
        // NotifyGrab/NotifyUngrab bracket a grab with a spurious focus
        // out/in pair (e.g. the passive XGrabButton behind click-to-focus);
        // NotifyWhileGrabbed, by contrast, is a genuine focus change that
        // merely happens during someone else's grab (AWT holds grabs
        // routinely) and must not be filtered.
        return mode == X11Ext.NotifyGrab || mode == X11Ext.NotifyUngrab;
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
