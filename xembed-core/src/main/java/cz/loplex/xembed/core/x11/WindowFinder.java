package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a process's own top-level window(s) by matching {@code _NET_WM_PID}
 * against the window manager's {@code _NET_CLIENT_LIST} — the same
 * technique {@code xdotool search --pid} uses. Deliberately avoids any
 * AWT/toolkit-internal window handle, so it works the same regardless of
 * JDK version or AWT implementation, at the cost of requiring a window
 * manager that publishes both EWMH properties.
 *
 * <p>Returns raw X11 window ids rather than JNA's {@link Window} type so
 * that callers outside {@code xembed-core} never need a compile-time
 * dependency on JNA, and so the type name doesn't collide with
 * {@link java.awt.Window}.
 */
public final class WindowFinder {

    private WindowFinder() {
    }

    public static List<Long> findTopLevelWindowsByPid(X11Display display, long pid) {
        Display raw = display.raw();
        Atom netClientList = X11Ext.INSTANCE.XInternAtom(raw, "_NET_CLIENT_LIST", false);
        Atom netWmPid = X11Ext.INSTANCE.XInternAtom(raw, "_NET_WM_PID", false);

        long[] clientWindowIds = X11Properties.readCardinal32(raw, display.defaultRootWindow(), netClientList);

        List<Long> matches = new ArrayList<>();
        for (long id : clientWindowIds) {
            long[] windowPid = X11Properties.readCardinal32(raw, new Window(id), netWmPid);
            if (windowPid.length == 1 && windowPid[0] == pid) {
                matches.add(id);
            }
        }
        return matches;
    }
}
