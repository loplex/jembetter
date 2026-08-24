package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Thin wrapper around {@code XReparentWindow} operating on raw X11 window
 * ids, so callers outside {@code xembed-core} never need a compile-time
 * dependency on JNA's X11 types.
 */
public final class Reparenting {

    private Reparenting() {
    }

    public static void reparent(X11Display display, long childWindowId, long newParentWindowId, int x, int y) {
        Display raw = display.raw();
        Window child = new Window(childWindowId);
        Window parent = new Window(newParentWindowId);
        X11Ext.INSTANCE.XReparentWindow(raw, child, parent, x, y);
        X11Ext.INSTANCE.XMapWindow(raw, child);
        X11Ext.INSTANCE.XFlush(raw);
    }
}
