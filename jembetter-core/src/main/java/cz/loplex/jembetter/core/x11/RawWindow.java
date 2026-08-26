package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Creates and destroys a plain, override-redirect top-level X11 window: no
 * window manager decoration, placement, or resizing, so its position and
 * size stay exactly whatever the caller last set — the shape a socket
 * window needs when it's kept positioned over a placeholder AWT/Swing
 * component ({@code getLocationOnScreen()} plus a resize/move listener)
 * instead of being reparented into the AWT window tree.
 *
 * <p>Returns/accepts raw window ids rather than JNA's {@code Window} type
 * so that callers outside {@code jembetter-core} never need a compile-time
 * dependency on JNA.
 */
public final class RawWindow {

    private RawWindow() {
    }

    /** Creates, maps and raises an override-redirect window at the given screen bounds. */
    public static long createOverrideRedirect(X11Display display, int x, int y, int width, int height) {
        Display raw = display.raw();

        RawWindowAttributes attributes = new RawWindowAttributes();
        attributes.override_redirect = 1;

        synchronized (X11Display.GLOBAL_LOCK) {
            Window window = X11Ext.INSTANCE.XCreateWindow(raw, display.defaultRootWindow(), x, y, width, height, 0,
                    X11Ext.CopyFromParent, X11Ext.InputOutput, null, new NativeLong(X11Ext.CWOverrideRedirect),
                    attributes);
            X11Ext.INSTANCE.XMapWindow(raw, window);
            X11Ext.INSTANCE.XRaiseWindow(raw, window);
            X11Ext.INSTANCE.XFlush(raw);
            return window.longValue();
        }
    }

    /**
     * Creates and maps a plain child window at local origin {@code (0,0)}
     * under {@code parentWindowId} — the shape a socket window needs when
     * it's a descendant of a real AWT component's own X11 window (e.g. a
     * {@code Canvas}) instead of a root-level override-redirect sibling:
     * position stays whatever the parent's coordinate space implies, and
     * normal X11 stacking/WM behavior treats it as part of the parent
     * window's subtree.
     */
    public static long createChild(X11Display display, long parentWindowId, int width, int height) {
        Display raw = display.raw();

        synchronized (X11Display.GLOBAL_LOCK) {
            Window window = X11Ext.INSTANCE.XCreateWindow(raw, new Window(parentWindowId), 0, 0, width, height, 0,
                    X11Ext.CopyFromParent, X11Ext.InputOutput, null, new NativeLong(0), null);
            X11Ext.INSTANCE.XMapWindow(raw, window);
            X11Ext.INSTANCE.XFlush(raw);
            return window.longValue();
        }
    }

    public static void destroy(X11Display display, long windowId) {
        Display raw = display.raw();
        synchronized (X11Display.GLOBAL_LOCK) {
            X11Ext.INSTANCE.XDestroyWindow(raw, new Window(windowId));
            X11Ext.INSTANCE.XFlush(raw);
        }
    }
}
