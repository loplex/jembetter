package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;

/**
 * Posts {@code WM_CLOSE} to a raw HWND, for the Win32 backend's
 * destroying-close (see {@code EmbedHostWin32#tryDestroy()}). Nominally
 * mirrors {@code jembetter-core-x11}'s {@code RawWindow#destroy} — but unlike
 * {@code XDestroyWindow}, which any X11 connection can issue against any
 * window regardless of which connection created it, Win32's own {@code
 * DestroyWindow} can <em>only</em> be called by the thread that created the
 * window (confirmed the hard way: a direct {@code DestroyWindow} call
 * against a genuinely separate process's HWND from here silently returns
 * {@code FALSE} and leaves the window intact — caught by this module's own
 * real-machine-style test coverage under Wine). {@code WM_CLOSE} is the
 * cross-process-safe equivalent: it's delivered via the target window's own
 * message queue, so its default handling in {@code DefWindowProc} (or the
 * embedded app's own {@code WM_CLOSE} handler, if it doesn't override the
 * default) ends up calling {@code DestroyWindow} on the window's own thread,
 * where it's actually allowed to succeed.
 *
 * <p><b>Best-effort, not guaranteed:</b> unlike {@code XDestroyWindow},
 * this only <em>asks</em> the target window to close — an app that
 * overrides {@code WM_CLOSE} to do something other than destroy itself
 * (e.g. hide instead, or prompt to save changes) is not forced to comply.
 * Callers that need destruction guaranteed regardless of the embedded
 * app's own cooperation still need to fall back to killing its process.
 */
public final class Win32Window {

    private Win32Window() {
    }

    /** Posts {@code WM_CLOSE} to {@code hwnd}, asking it to close itself. */
    public static void destroy(long hwnd) {
        User32.INSTANCE.PostMessage(toHwnd(hwnd), WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
