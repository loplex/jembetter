package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Cursor;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Passive, whole-window button grab used for click-to-focus: a {@code
 * ButtonPress} landing on the grabbed window is delivered to the grabbing
 * connection first (frozen, via {@code GrabModeSync}) instead of the
 * connection that actually owns the window, so the grabbing side can react
 * (e.g. set input focus) before the window's own client ever sees the
 * press — then {@link #replay} lets it through as if nothing intervened.
 * Mirrors how a real window manager implements click-to-focus.
 */
public final class ButtonGrab {

    private ButtonGrab() {
    }

    /**
     * Grabs every button/modifier combination on {@code windowId}, pointer
     * events frozen ({@code GrabModeSync}) until {@link #replay}, keyboard
     * events left unfrozen ({@code GrabModeAsync}, since nothing here reacts
     * to keyboard grabs).
     */
    public static void install(X11Display display, long windowId) {
        Display raw = display.raw();
        Window window = new Window(windowId);
        synchronized (X11Display.GLOBAL_LOCK) {
            X11Ext.INSTANCE.XGrabButton(raw, X11Ext.AnyButton, X11Ext.AnyModifier, window, 0,
                    X11Ext.ButtonPressMask, X11Ext.GrabModeSync, X11Ext.GrabModeAsync, Window.None, Cursor.None);
            X11Ext.INSTANCE.XFlush(raw);
        }
    }

    /** Undoes {@link #install}. */
    public static void uninstall(X11Display display, long windowId) {
        Display raw = display.raw();
        Window window = new Window(windowId);
        synchronized (X11Display.GLOBAL_LOCK) {
            X11Ext.INSTANCE.XUngrabButton(raw, X11Ext.AnyButton, X11Ext.AnyModifier, window);
            X11Ext.INSTANCE.XFlush(raw);
        }
    }

    /**
     * Releases the pointer freeze a grabbed {@code ButtonPress} caused,
     * replaying it through to the grabbed window's own client normally.
     * Must be called on the same connection that installed the grab,
     * promptly after every grabbed press, or all pointer input system-wide
     * hangs until it is.
     */
    public static void replay(X11Display display) {
        Display raw = display.raw();
        synchronized (X11Display.GLOBAL_LOCK) {
            X11Ext.INSTANCE.XAllowEvents(raw, X11Ext.ReplayPointer, new NativeLong(X11Ext.CurrentTime));
            X11Ext.INSTANCE.XFlush(raw);
        }
    }
}
