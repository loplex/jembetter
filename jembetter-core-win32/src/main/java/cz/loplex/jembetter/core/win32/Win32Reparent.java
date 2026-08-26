package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Thin wrapper around {@code SetParent} plus the style-flip that makes the
 * new parent/child relationship stick, operating on raw HWND values, so
 * callers outside {@code jembetter-core-win32} never need a compile-time
 * dependency on JNA's Win32 types. Mirrors {@code jembetter-core}'s {@code
 * Reparenting} — see this module's package-info for the "unverified against a real
 * Windows machine" caveat that still applies to this flow.
 */
public final class Win32Reparent {

    private static final int STYLE_BITS_TO_CLEAR =
            WinUser.WS_POPUP | WinUser.WS_CAPTION | WinUser.WS_SYSMENU | WinUser.WS_THICKFRAME;

    private Win32Reparent() {
    }

    /**
     * Reparents {@code childHwnd} under {@code newParentHwnd}: clears the
     * top-level-window style bits ({@code WS_POPUP|WS_CAPTION|WS_SYSMENU|
     * WS_THICKFRAME}), sets {@code WS_CHILD}, then {@code SetParent} and
     * repositions to {@code (x, y)} in the new parent's client coordinates.
     * Sizing is a separate concern — see {@link Win32WindowGeometry}.
     */
    public static void reparent(long childHwnd, long newParentHwnd, int x, int y) {
        HWND child = toHwnd(childHwnd);
        HWND newParent = toHwnd(newParentHwnd);

        int style = User32.INSTANCE.GetWindowLong(child, WinUser.GWL_STYLE);
        style = (style & ~STYLE_BITS_TO_CLEAR) | WinUser.WS_CHILD;
        User32.INSTANCE.SetWindowLong(child, WinUser.GWL_STYLE, style);

        User32.INSTANCE.SetParent(child, newParent);
        User32.INSTANCE.SetWindowPos(child, null, x, y, 0, 0,
                WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER | WinUser.SWP_SHOWWINDOW);
    }

    /**
     * The reverse of {@link #reparent}: restores the {@code WS_POPUP} style
     * bit (dropping {@code WS_CHILD}) and reparents {@code childHwnd} back to
     * the desktop window, repositioning it to {@code (x, y)} in screen
     * coordinates so it doesn't visually jump.
     */
    public static void release(long childHwnd, int x, int y) {
        HWND child = toHwnd(childHwnd);

        int style = User32.INSTANCE.GetWindowLong(child, WinUser.GWL_STYLE);
        style = (style & ~WinUser.WS_CHILD) | WinUser.WS_POPUP;
        User32.INSTANCE.SetWindowLong(child, WinUser.GWL_STYLE, style);

        User32.INSTANCE.SetParent(child, User32.INSTANCE.GetDesktopWindow());
        User32.INSTANCE.SetWindowPos(child, null, x, y, 0, 0,
                WinUser.SWP_NOSIZE | WinUser.SWP_NOZORDER | WinUser.SWP_SHOWWINDOW);
    }

    /** {@code GetParent(hwnd)}, or {@code 0} if the window has no parent (e.g. it's desktop-parented). */
    public static long parentOf(long hwnd) {
        HWND parent = User32.INSTANCE.GetParent(toHwnd(hwnd));
        return parent == null ? 0 : Pointer.nativeValue(parent.getPointer());
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
