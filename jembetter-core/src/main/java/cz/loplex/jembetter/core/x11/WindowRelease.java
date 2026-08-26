package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

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

    private static final int POLL_INTERVAL_MILLIS = 20;
    private static final int POLL_ATTEMPTS = 100;

    private WindowRelease() {
    }

    /**
     * Withdraws {@code windowId} (ICCCM's standard {@code XWithdrawWindow}:
     * unmap plus a synthetic {@code UnmapNotify} to root) and waits for the
     * window manager to actually let go of it — observed as its parent
     * becoming the root window again, which is how window managers give up
     * a managed client's decoration frame. Best-effort: if the window
     * manager doesn't release it within the poll budget (e.g. no window
     * manager is running at all, so there was never a frame to begin with —
     * the common case in this library's own test harness for windows that
     * never called {@code setVisible(true)}), returns anyway rather than
     * blocking indefinitely; the caller's own reparent still goes through
     * either way, just without this method's guarantee against a race.
     */
    public static void release(X11Display display, long windowId) {
        Display raw = display.raw();
        Window window = new Window(windowId);
        int screen = X11Ext.INSTANCE.XDefaultScreen(raw);
        X11Ext.INSTANCE.XWithdrawWindow(raw, window, screen);
        X11Ext.INSTANCE.XSync(raw, false);
        waitForRootParent(display, windowId);
    }

    private static void waitForRootParent(X11Display display, long windowId) {
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
    }
}
