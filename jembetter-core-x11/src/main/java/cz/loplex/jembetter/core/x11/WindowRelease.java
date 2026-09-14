package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Releases a window from window manager control before {@link Reparenting}
 * moves it somewhere else, per ICCCM section 4.1.4 ("Changing Window
 * State"). A client process embedded via this library typically arrives as
 * an ordinary, already-mapped top-level window (that's the whole point of
 * embedding an existing application rather than one written against this
 * protocol from scratch), which means a window manager has already adopted
 * it — reparented it under its own decoration frame — by the time {@link
 * Reparenting#reparent} runs. Skipping this step doesn't make that reparent
 * fail, but some window managers (confirmed with openbox) treat the
 * implicit unmap {@code XReparentWindow} performs on any mapped window as
 * an ordinary, unrequested unmap of one of their own managed clients (e.g.
 * indistinguishable from iconification) and react by re-adopting the window
 * back under a decoration frame immediately afterward — undoing the
 * reparent from the window manager's perspective within milliseconds of it
 * succeeding on the wire.
 */
public final class WindowRelease {

    private static final Logger LOG = LoggerFactory.getLogger(WindowRelease.class);

    private static final int POLL_INTERVAL_MILLIS = 20;
    private static final int POLL_ATTEMPTS = 100;

    private WindowRelease() {
    }

    /**
     * Withdraws {@code windowId} (ICCCM's standard {@code XWithdrawWindow}:
     * unmap plus a synthetic {@code UnmapNotify} to root) and waits for the
     * window manager to actually let go of it — observed as its parent
     * becoming the root window again, which is how window managers give up
     * a managed client's decoration frame. Best-effort: it returns anyway
     * rather than blocking indefinitely, and the caller's own reparent goes
     * through either way, just without this method's guarantee against the
     * race described above.
     *
     * <p>It does distinguish the two reasons for giving up, which it used to
     * conflate. With no window manager running there is no frame and never
     * will be one — the common case in this library's own test harness — so
     * there is nothing to wait for and it does not wait. With a window
     * manager running that has not let go within the budget, something is
     * actually wrong, and that is logged rather than passing as the same
     * silent non-event.
     */
    public static void release(X11Display display, long windowId) {
        Window window = new Window(windowId);
        display.ifOpen(raw -> {
            int screen = X11Ext.INSTANCE.XDefaultScreen(raw);
            X11Ext.INSTANCE.XWithdrawWindow(raw, window, screen);
            X11Ext.INSTANCE.XSync(raw, false);
        });
        waitForRootParent(display, windowId);
    }

    private static void waitForRootParent(X11Display display, long windowId) {
        if (!windowManagerRunning(display)) {
            return;
        }
        long rootWindowId = display.defaultRootWindow().longValue();
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            if (WindowTree.parentOf(display, windowId) == rootWindowId) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("Window manager did not release window {} within {}ms of XWithdrawWindow; "
                + "the reparent that follows may be undone by it",
                windowId, POLL_ATTEMPTS * POLL_INTERVAL_MILLIS);
    }

    /**
     * Whether a window manager is running at all, so that waiting for one to
     * act means something.
     *
     * <p>Two signals, either of which counts: the ICCCM manager selection
     * {@code WM_S<screen>}, which is how a window manager claims a screen,
     * and EWMH's {@code _NET_SUPPORTING_WM_CHECK} on the root window. Most
     * window managers set both; asking for both keeps an older ICCCM-only
     * one from being read as absent. A closed connection counts as no window
     * manager — there is nothing left to wait on either way.
     */
    private static boolean windowManagerRunning(X11Display display) {
        return display.ifOpen(raw -> {
            int screen = X11Ext.INSTANCE.XDefaultScreen(raw);
            Window selectionOwner = X11Ext.INSTANCE.XGetSelectionOwner(raw,
                    X11Ext.INSTANCE.XInternAtom(raw, "WM_S" + screen, false));
            if (selectionOwner != null && selectionOwner.longValue() != 0) {
                return true;
            }
            Atom supportingWmCheck = X11Ext.INSTANCE.XInternAtom(raw, "_NET_SUPPORTING_WM_CHECK", false);
            return X11Properties.readCardinal32(raw, X11Ext.INSTANCE.XDefaultRootWindow(raw),
                    supportingWmCheck).length > 0;
        }, false);
    }
}
