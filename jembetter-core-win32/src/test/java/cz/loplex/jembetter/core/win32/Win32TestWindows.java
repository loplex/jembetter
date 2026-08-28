package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.INPUT;

/**
 * Test-only helper: creates/destroys plain top-level windows via the
 * always-registered {@code "STATIC"} window class, so the primitive tests in
 * this package don't each need their own {@code RegisterClassEx}/WNDPROC
 * plumbing just to have a real HWND to operate on.
 */
final class Win32TestWindows {

    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;

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

    /**
     * A borderless, visible top-level window at an exact screen rect, so a
     * click-coordinate hit-test has a known target: {@code WS_POPUP} keeps
     * {@code GetWindowRect} equal to the position/size passed here (no
     * caption or frame to offset it).
     */
    static long createVisibleTopLevelWindowAt(String title, int x, int y, int width, int height) {
        HWND hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC", title, WinUser.WS_POPUP | WinUser.WS_VISIBLE,
                x, y, width, height, null, null, null, null);
        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowEx failed, GetLastError=" + Kernel32.INSTANCE.GetLastError());
        }
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
        return Pointer.nativeValue(hwnd.getPointer());
    }

    static RECT rectOf(long hwnd) {
        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(toHwnd(hwnd), rect);
        return rect;
    }

    /** Synthesizes a left click at absolute screen coordinates via {@code SendInput}. */
    static void clickAt(int screenX, int screenY) {
        User32.INSTANCE.SetCursorPos(screenX, screenY);
        INPUT[] inputs = (INPUT[]) new INPUT().toArray(2);
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].type = new DWORD(INPUT.INPUT_MOUSE);
            inputs[i].input.setType("mi");
            inputs[i].input.mi.dx = new LONG(0);
            inputs[i].input.mi.dy = new LONG(0);
            inputs[i].input.mi.mouseData = new DWORD(0);
            inputs[i].input.mi.time = new DWORD(0);
            inputs[i].input.mi.dwExtraInfo = new ULONG_PTR(0);
            inputs[i].input.mi.dwFlags = new DWORD(i == 0 ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);
            inputs[i].write();
        }
        User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size());
    }

    static void destroyWindow(long hwnd) {
        User32.INSTANCE.DestroyWindow(toHwnd(hwnd));
    }

    static boolean isWindow(long hwnd) {
        return User32.INSTANCE.IsWindow(toHwnd(hwnd));
    }

    static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
