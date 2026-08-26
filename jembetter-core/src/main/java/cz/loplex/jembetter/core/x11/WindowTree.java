package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.WindowByReference;
import com.sun.jna.platform.unix.X11.XWindowAttributes;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Read-only window tree queries ({@code XQueryTree}, {@code
 * XGetWindowAttributes}) for confirming what actually happened to a window
 * rather than trusting a client to report it — needed for embedding a
 * toolkit-opaque client that can't be relied on to round-trip any
 * cooperative signal back to the embedder.
 */
public final class WindowTree {

    private WindowTree() {
    }

    /** {@code windowId}'s current parent window id, per {@code XQueryTree}. */
    public static long parentOf(X11Display display, long windowId) {
        Display raw = display.raw();
        WindowByReference rootReturn = new WindowByReference();
        WindowByReference parentReturn = new WindowByReference();
        PointerByReference childrenReturn = new PointerByReference();
        IntByReference nchildrenReturn = new IntByReference();
        X11Ext.INSTANCE.XQueryTree(raw, new Window(windowId), rootReturn, parentReturn, childrenReturn,
                nchildrenReturn);
        if (childrenReturn.getValue() != null) {
            X11Ext.INSTANCE.XFree(childrenReturn.getValue());
        }
        return parentReturn.getValue().longValue();
    }

    /** Whether {@code windowId} is currently mapped (viewable), per {@code XGetWindowAttributes}'s {@code map_state}. */
    public static boolean isMapped(X11Display display, long windowId) {
        XWindowAttributes attributes = new XWindowAttributes();
        X11Ext.INSTANCE.XGetWindowAttributes(display.raw(), new Window(windowId), attributes);
        return attributes.map_state == X11Ext.IsViewable;
    }
}
