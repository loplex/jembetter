package cz.loplex.xembed.core.xembed;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XClientMessageEvent;
import com.sun.jna.platform.unix.X11.XEvent;
import com.sun.jna.platform.unix.X11.XPropertyEvent;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.x11.X11Ext;

import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/**
 * Watches an embedder's own window for the two kinds of events an XEmbed
 * client sends the other way, toward the embedder: inbound {@code _XEMBED}
 * ClientMessages ({@code XEMBED_REQUEST_FOCUS}, {@code XEMBED_FOCUS_NEXT}/
 * {@code PREV}, ...) targeted at the embedder window itself, and
 * PropertyNotify on the currently embedded client's {@code _XEMBED_INFO}.
 *
 * <p><strong>Must be constructed with the exact {@link X11Display} that
 * created (owns) the embedder window.</strong> XEmbed ClientMessages are
 * sent with {@code XSendEvent}'s {@code event_mask} argument set to zero,
 * which Xlib delivers only to the connection that created the destination
 * window — not to whichever connection happens to have selected matching
 * input, and not even back to the sender. This was confirmed against a live
 * X server before relying on it: a second, unrelated connection sees
 * nothing. (PropertyNotify has no such restriction — any connection that
 * calls {@code XSelectInput} for it on a window receives it, regardless of
 * which connection owns that window.)
 *
 * <p>Runs a background thread that shares the caller's {@code X11Display}
 * rather than opening its own, which is why {@link X11Display#open} arranges
 * for {@code XInitThreads} to have been called first.
 */
public final class XEmbedInboundWatcher implements AutoCloseable {

    private final X11Display display;
    private final Window embedderWindow;
    private final Atom xembedAtom;
    private final Atom xembedInfoAtom;
    private final Thread thread;

    private volatile long embeddedInfoWindowId = -1;
    private volatile BiConsumer<XEmbedMessage, Long> onClientMessage = (message, detail) -> {
    };
    private volatile LongConsumer onEmbeddedInfoChanged = windowId -> {
    };

    public XEmbedInboundWatcher(X11Display display, long embedderWindowId) {
        this.display = display;
        this.embedderWindow = new Window(embedderWindowId);
        this.xembedAtom = X11Ext.INSTANCE.XInternAtom(display.raw(), XEmbedAtoms.XEMBED, false);
        this.xembedInfoAtom = X11Ext.INSTANCE.XInternAtom(display.raw(), XEmbedAtoms.XEMBED_INFO, false);
        this.thread = new Thread(this::loop, "xembed-inbound-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Handler invoked, on this watcher's own thread, for each inbound {@code _XEMBED} ClientMessage. */
    public void onClientMessage(BiConsumer<XEmbedMessage, Long> handler) {
        onClientMessage = handler;
    }

    /** Handler invoked, on this watcher's own thread, when the watched window's {@code _XEMBED_INFO} changes. */
    public void onEmbeddedInfoChanged(LongConsumer handler) {
        onEmbeddedInfoChanged = handler;
    }

    /** Starts tracking {@code _XEMBED_INFO} PropertyNotify for the currently embedded client window. */
    public void watchEmbeddedInfo(long clientWindowId) {
        X11Ext.INSTANCE.XSelectInput(display.raw(), new Window(clientWindowId),
                new NativeLong(X11Ext.PropertyChangeMask));
        X11Ext.INSTANCE.XFlush(display.raw());
        embeddedInfoWindowId = clientWindowId;
    }

    /** Stops tracking {@code _XEMBED_INFO}, e.g. once the embedded client has detached. */
    public void stopWatchingEmbeddedInfo() {
        embeddedInfoWindowId = -1;
    }

    private void loop() {
        XEvent event = new XEvent();
        while (!Thread.currentThread().isInterrupted()) {
            boolean handled = false;
            if (X11Ext.INSTANCE.XCheckTypedWindowEvent(display.raw(), embedderWindow, X11Ext.ClientMessage, event)) {
                dispatchClientMessage(event);
                handled = true;
            }
            long watchedId = embeddedInfoWindowId;
            if (watchedId >= 0 && X11Ext.INSTANCE.XCheckTypedWindowEvent(display.raw(), new Window(watchedId),
                    X11Ext.PropertyNotify, event)) {
                dispatchPropertyNotify(event, watchedId);
                handled = true;
            }
            if (!handled) {
                idle();
            }
        }
    }

    private void dispatchClientMessage(XEvent event) {
        event.setType(XClientMessageEvent.class);
        event.read();
        if (event.xclient.message_type.longValue() != xembedAtom.longValue()) {
            return;
        }
        long opcode = event.xclient.data.l[1].longValue();
        long detail = event.xclient.data.l[2].longValue();
        try {
            onClientMessage.accept(XEmbedMessage.fromOpcode(opcode), detail);
        } catch (RuntimeException e) {
            // A misbehaving handler must not take the watcher thread down.
            e.printStackTrace();
        }
    }

    private void dispatchPropertyNotify(XEvent event, long windowId) {
        event.setType(XPropertyEvent.class);
        event.read();
        if (event.xproperty.atom.longValue() != xembedInfoAtom.longValue()) {
            return;
        }
        try {
            onEmbeddedInfoChanged.accept(windowId);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void idle() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the background thread. Does not close the shared {@link X11Display}; the caller owns that. */
    @Override
    public void close() {
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
