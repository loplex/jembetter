package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Thin wrapper around {@code SetParent} plus the style-flip that makes the
 * new parent/child relationship stick, operating on raw HWND values, so
 * callers outside {@code jembetter-core-win32} never need a compile-time
 * dependency on JNA's Win32 types. Mirrors {@code jembetter-core-x11}'s {@code
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
     * repositions to {@code (x, y)} in the new parent's client coordinates,
     * leaving the size alone. Prefer {@link #reparent(long, long, int, int,
     * int, int)} when the caller already knows the size it wants — see there
     * for why the two-call form is visible to the embedded client.
     */
    public static void reparent(long childHwnd, long newParentHwnd, int x, int y) {
        reparent(childHwnd, newParentHwnd, x, y, 0, 0, true);
    }

    /**
     * Reparents {@code childHwnd} under {@code newParentHwnd} and gives it
     * {@code width}x{@code height} in the same operation.
     *
     * <p>Worth preferring over {@link #reparent(long, long, int, int)}
     * followed by {@link Win32WindowGeometry#moveResize}, which is what this
     * replaces: that sequence changes the window's geometry twice, and the
     * embedded client sees both. Clearing {@code WS_CAPTION}/{@code
     * WS_THICKFRAME} shrinks the client area on its own — a decorated window
     * Windows refused to make narrower than its title-bar buttons can drop to
     * a fraction of that once undecorated — so the first change reports a size
     * the host never asked for and never intended, purely because the move
     * happened in two steps. A client driving layout from {@code onResized}
     * then lays out once against a size that existed only in between.
     *
     * <p>Folding the size into the same {@code SetWindowPos} makes the
     * intermediate geometry unobservable rather than merely brief. This is a
     * Win32-only concern: X11 keeps decorations in a separate window-manager
     * frame, so reparenting a client there does not resize it at all.
     */
    public static void reparent(long childHwnd, long newParentHwnd, int x, int y, int width, int height) {
        reparent(childHwnd, newParentHwnd, x, y, width, height, false);
    }

    private static void reparent(long childHwnd, long newParentHwnd, int x, int y,
            int width, int height, boolean keepSize) {
        HWND child = toHwnd(childHwnd);
        HWND newParent = toHwnd(newParentHwnd);

        int style = User32.INSTANCE.GetWindowLong(child, WinUser.GWL_STYLE);
        style = (style & ~STYLE_BITS_TO_CLEAR) | WinUser.WS_CHILD;
        User32.INSTANCE.SetWindowLong(child, WinUser.GWL_STYLE, style);

        User32.INSTANCE.SetParent(child, newParent);
        int flags = WinUser.SWP_NOZORDER | WinUser.SWP_SHOWWINDOW | (keepSize ? WinUser.SWP_NOSIZE : 0);
        User32.INSTANCE.SetWindowPos(child, null, x, y, width, height, flags);
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

    /**
     * {@code GetParent(hwnd)}, or {@code 0} if the window has no parent —
     * which includes being parented to the desktop, where {@code GetParent}
     * reports the desktop window rather than nothing.
     *
     * <p>That distinction is not cosmetic. {@code GetParent} returns a real
     * parent for any {@code WS_CHILD} window, and a top-level window's parent
     * in Windows' own window tree *is* the desktop — so a window carrying
     * {@code WS_CHILD} without having been given a parent of its own reads as
     * a child of the desktop. {@link #reparent} produces exactly that state
     * for as long as it takes to get from its style flip to its {@code
     * SetParent}, and a watcher polling the window in that gap sees a genuine
     * change to a genuine handle: measured on CI 2026-09-13 at 3-4 polls in
     * 10, long enough for a client to report the desktop as the host that
     * embedded it. Reporting 0 makes that interval read as unchanged rather
     * than as an embed nobody performed.
     *
     * <p>Nothing is lost by it: being parented to the desktop is what every
     * unembedded top-level window already is, and is what {@link #release}
     * puts a window back to. It matches {@code jembetter-core-x11}, where a
     * client reparented to the root window is released rather than embedded.
     */
    public static long parentOf(long hwnd) {
        HWND parent = User32.INSTANCE.GetParent(toHwnd(hwnd));
        if (parent == null) {
            return 0;
        }
        long parentHwnd = Pointer.nativeValue(parent.getPointer());
        return parentHwnd == desktopWindow() ? 0 : parentHwnd;
    }

    private static long desktopWindow() {
        return Pointer.nativeValue(User32.INSTANCE.GetDesktopWindow().getPointer());
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
