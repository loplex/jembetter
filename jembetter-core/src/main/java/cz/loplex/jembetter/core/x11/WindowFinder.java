package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a process's own top-level window(s) by matching {@code _NET_WM_PID}
 * against the window manager's {@code _NET_CLIENT_LIST} — the same
 * technique {@code xdotool search --pid} uses. Deliberately avoids any
 * AWT/toolkit-internal window handle, so it works the same regardless of
 * JDK version or AWT implementation, at the cost of requiring a window
 * manager that publishes both EWMH properties.
 *
 * <p>Returns raw X11 window ids rather than JNA's {@link Window} type so
 * that callers outside {@code jembetter-core} never need a compile-time
 * dependency on JNA, and so the type name doesn't collide with
 * {@link java.awt.Window}.
 */
public final class WindowFinder {

    private WindowFinder() {
    }

    public static List<Long> findTopLevelWindowsByPid(X11Display display, long pid) {
        Display raw = display.raw();
        synchronized (X11Display.GLOBAL_LOCK) {
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

    /**
     * Narrows {@link #findTopLevelWindowsByPid} to those whose {@code
     * WM_CLASS} class component (the same string {@code xprop WM_CLASS}
     * prints as the second, quoted value) equals {@code wmClass} — for
     * disambiguating a process that owns more than one top-level window.
     */
    public static List<Long> findTopLevelWindowsByPidAndClass(X11Display display, long pid, String wmClass) {
        List<Long> matches = new ArrayList<>();
        for (long id : findTopLevelWindowsByPid(display, pid)) {
            if (readWmClass(display, id).map(wmClass::equals).orElse(false)) {
                matches.add(id);
            }
        }
        return matches;
    }

    /**
     * Reads a window's {@code WM_CLASS} class component (the second of the
     * two NUL-separated strings the property holds; the first is the
     * instance name, which this deliberately ignores).
     */
    public static Optional<String> readWmClass(X11Display display, long windowId) {
        List<String> parts;
        synchronized (X11Display.GLOBAL_LOCK) {
            parts = X11Properties.readStringList8(display.raw(), new Window(windowId), X11.XA_WM_CLASS);
        }
        return parts.size() < 2 ? Optional.empty() : Optional.of(parts.get(1));
    }
}
