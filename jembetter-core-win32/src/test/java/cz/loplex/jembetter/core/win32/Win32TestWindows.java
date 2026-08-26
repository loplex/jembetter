package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;

/**
 * Test-only helper: creates/destroys plain top-level windows via the
 * always-registered {@code "STATIC"} window class, so the primitive tests in
 * this package don't each need their own {@code RegisterClassEx}/WNDPROC
 * plumbing just to have a real HWND to operate on.
 */
final class Win32TestWindows {

    private Win32TestWindows() {
    }

    static long createTopLevelWindow(String title) {
        HWND hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC", title, WinUser.WS_OVERLAPPEDWINDOW,
                0, 0, 50, 50, null, null, null, null);
        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowEx failed, GetLastError=" + Kernel32.INSTANCE.GetLastError());
        }
        return Pointer.nativeValue(hwnd.getPointer());
    }

    static void destroyWindow(long hwnd) {
        User32.INSTANCE.DestroyWindow(toHwnd(hwnd));
    }

    static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
