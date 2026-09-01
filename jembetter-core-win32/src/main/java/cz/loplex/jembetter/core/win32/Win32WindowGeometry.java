package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;

/**
 * Thin wrapper around {@code MoveWindow}/{@code ShowWindow} operating on a
 * raw HWND value. Mirrors {@code jembetter-core-x11}'s {@code WindowGeometry}.
 */
public final class Win32WindowGeometry {

    private Win32WindowGeometry() {
    }

    /** Moves and resizes {@code hwnd} within its current parent's client coordinates, repainting it. */
    public static void moveResize(long hwnd, int x, int y, int width, int height) {
        User32.INSTANCE.MoveWindow(toHwnd(hwnd), x, y, width, height, true);
    }

    /**
     * {@code hwnd}'s current on-screen position (its top-left corner, via
     * {@code GetWindowRect}) — for relocating a window from one parent to
     * another (e.g. releasing an embedded client back to the desktop window)
     * without it visually jumping. Mirrors {@code jembetter-core-x11}'s
     * {@code WindowGeometry#rootPosition}.
     */
    public static int[] screenPosition(long hwnd) {
        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(toHwnd(hwnd), rect);
        return new int[] { rect.left, rect.top };
    }

    /** Shows or hides {@code hwnd}, e.g. in response to a client clearing/setting its own XEMBED_MAPPED-equivalent state. */
    public static void setMapped(long hwnd, boolean mapped) {
        User32.INSTANCE.ShowWindow(toHwnd(hwnd), mapped ? WinUser.SW_SHOW : WinUser.SW_HIDE);
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
