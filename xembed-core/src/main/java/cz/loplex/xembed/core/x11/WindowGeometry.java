package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.WindowByReference;
import com.sun.jna.ptr.IntByReference;

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
        // XSync, not just XFlush: callers immediately read this window's
        // geometry back on the same connection (e.g. to decide whether a
        // follow-up resize is still needed), which needs the server to have
        // actually processed the request, not just have it written to the
        // wire.
        X11Ext.INSTANCE.XSync(raw, false);
    }

    /** Raises {@code windowId} to the top of its siblings' stacking order. */
    public static void raise(X11Display display, long windowId) {
        Display raw = display.raw();
        X11Ext.INSTANCE.XRaiseWindow(raw, new Window(windowId));
        X11Ext.INSTANCE.XFlush(raw);
    }

    /**
     * Translates {@code windowId}'s own origin (0,0) into the root window's
     * coordinate space, i.e. its current on-screen position — for
     * relocating a window from one parent to another (e.g. releasing an
     * embedded client back to the root window) without it visually jumping.
     */
    public static int[] rootPosition(X11Display display, long windowId) {
        Display raw = display.raw();
        IntByReference rootX = new IntByReference();
        IntByReference rootY = new IntByReference();
        X11Ext.INSTANCE.XTranslateCoordinates(raw, new Window(windowId), display.defaultRootWindow(), 0, 0, rootX,
                rootY, new WindowByReference());
        return new int[] { rootX.getValue(), rootY.getValue() };
    }

    /** Maps or unmaps {@code windowId}, e.g. in response to a client clearing/setting its own XEMBED_MAPPED flag. */
    public static void setMapped(X11Display display, long windowId, boolean mapped) {
        Display raw = display.raw();
        Window window = new Window(windowId);
        if (mapped) {
            X11Ext.INSTANCE.XMapWindow(raw, window);
        } else {
            X11Ext.INSTANCE.XUnmapWindow(raw, window);
        }
        X11Ext.INSTANCE.XFlush(raw);
    }
}
