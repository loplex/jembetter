package cz.loplex.jembetter.client;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Test-only helper: creates/destroys a plain top-level window via the
 * always-registered {@code "STATIC"} window class, standing in for "a real
 * host window" in {@link EmbedPlugWin32Test} without needing a second JVM.
 * Mirrors {@code jembetter-core-win32}'s package-private {@code
 * Win32TestWindows}, duplicated here since that one isn't visible outside
 * its own module.
 */
final class Win32TestWindow {

    private Win32TestWindow() {
    }

    static long create(String title) {
        HWND hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC", title, WinUser.WS_OVERLAPPEDWINDOW,
                0, 0, 50, 50, null, null, null, null);
        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowEx failed, GetLastError=" + Kernel32.INSTANCE.GetLastError());
        }
        return Pointer.nativeValue(hwnd.getPointer());
    }

    static void destroy(long hwnd) {
        User32.INSTANCE.DestroyWindow(toHwnd(hwnd));
    }

    static boolean exists(long hwnd) {
        return User32.INSTANCE.IsWindow(toHwnd(hwnd));
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
