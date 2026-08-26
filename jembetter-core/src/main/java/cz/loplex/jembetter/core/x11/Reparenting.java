package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Thin wrapper around {@code XReparentWindow} operating on raw X11 window
 * ids, so callers outside {@code jembetter-core} never need a compile-time
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
        // Adding the (foreign-owned) child to this connection's save-set is
        // what lets the child survive this connection closing later: instead
        // of being destroyed along with the rest of the embedder's subtree,
        // the server reparents it back to the root window and maps it,
        // which is what WindowReparentWatcher on the embedded side detects.
        X11Ext.INSTANCE.XAddToSaveSet(raw, child);
        X11Ext.INSTANCE.XMapWindow(raw, child);
        // XSync, not just XFlush: callers hand childWindowId off to code
        // using other X11 connections (e.g. WindowDeathWatcher) right after
        // this returns, which needs the server to have actually processed
        // the reparent/map, not just have them written to the wire.
        X11Ext.INSTANCE.XSync(raw, false);
    }

    /**
     * The reverse of {@link #reparent}: for a host voluntarily releasing a
     * still-live embedded client, as opposed to only finding out about a
     * detach after the fact when its own connection closes. Removes {@code
     * childWindowId} from this connection's save-set first — see {@link
     * X11Ext#XRemoveFromSaveSet} for why — then reparents it to {@code
     * newParentWindowId} and maps it.
     */
    public static void release(X11Display display, long childWindowId, long newParentWindowId, int x, int y) {
        Display raw = display.raw();
        Window child = new Window(childWindowId);
        Window parent = new Window(newParentWindowId);
        X11Ext.INSTANCE.XRemoveFromSaveSet(raw, child);
        X11Ext.INSTANCE.XReparentWindow(raw, child, parent, x, y);
        X11Ext.INSTANCE.XMapWindow(raw, child);
        X11Ext.INSTANCE.XSync(raw, false);
    }
}
