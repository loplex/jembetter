package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Thin wrapper around {@code XSetInputFocus} operating on a raw window id.
 */
public final class InputFocus {

    private InputFocus() {
    }

    /** Points the X server's keyboard input focus at {@code windowId}. */
    public static void set(X11Display display, long windowId) {
        Display raw = display.raw();
        X11Ext.INSTANCE.XSetInputFocus(raw, new Window(windowId), X11Ext.RevertToParent,
                new NativeLong(X11Ext.CurrentTime));
        X11Ext.INSTANCE.XFlush(raw);
    }
}
