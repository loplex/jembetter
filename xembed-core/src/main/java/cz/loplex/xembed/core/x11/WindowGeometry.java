package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Thin wrapper around {@code XMoveResizeWindow} operating on a raw window
 * id, so callers outside {@code xembed-core} never need a compile-time
 * dependency on JNA's X11 types.
 */
public final class WindowGeometry {

    private WindowGeometry() {
    }

    public static void moveResize(X11Display display, long windowId, int x, int y, int width, int height) {
        Display raw = display.raw();
        X11Ext.INSTANCE.XMoveResizeWindow(raw, new Window(windowId), x, y, width, height);
        X11Ext.INSTANCE.XFlush(raw);
    }
}
