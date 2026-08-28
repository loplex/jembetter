package cz.loplex.jembetter.win32check;

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
 * Helper shared by {@link FocusFallbackCheck} / {@link
 * ClickWatcherCaveatsCheck}: plain borderless top-level windows via the
 * always-registered {@code "STATIC"} window class, plus {@code SendInput}
 * mouse/keyboard synthesis — the same approach {@code
 * jembetter-core-win32}'s own {@code Win32TestWindows} test helper uses,
 * copied here rather than depended on so this directory stays buildable
 * against {@code target/classes} + the jna jars alone (see README.md).
 */
final class CheckWindows {

    static final int MOUSEEVENTF_MOVE = 0x0001;
    static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
    static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;
    static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    static final int MOUSEEVENTF_LEFTUP = 0x0004;

    static final int VK_MENU = 0x12;

    private CheckWindows() {
    }

    /**
     * A borderless, visible top-level window at an exact screen rect, so a
     * click-coordinate hit-test has a known target: {@code WS_POPUP} keeps
     * {@code GetWindowRect} equal to the position/size passed here.
     */
    static long createVisibleTopLevelWindowAt(String title, int x, int y, int width, int height) {
        HWND hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC", title, WinUser.WS_POPUP | WinUser.WS_VISIBLE,
                x, y, width, height, null, null, null, null);
        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowEx failed, GetLastError="
                    + Kernel32.INSTANCE.GetLastError());
        }
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
        return Pointer.nativeValue(hwnd.getPointer());
    }

    static RECT rectOf(long hwnd) {
        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(toHwnd(hwnd), rect);
        return rect;
    }

    static boolean isWindow(long hwnd) {
        return User32.INSTANCE.IsWindow(toHwnd(hwnd));
    }

    static void destroyWindow(long hwnd) {
        User32.INSTANCE.DestroyWindow(toHwnd(hwnd));
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

    /**
     * Synthesizes {@code count} bare mouse-move events (no button) as fast as
     * {@code SendInput} accepts them — the raw system-wide mouse traffic a
     * {@code WH_MOUSE_LL} hook has to funnel through the hooking process.
     */
    static void spamMouseMoves(int count) {
        for (int i = 0; i < count; i++) {
            INPUT[] inputs = (INPUT[]) new INPUT().toArray(1);
            inputs[0].type = new DWORD(INPUT.INPUT_MOUSE);
            inputs[0].input.setType("mi");
            // Nudge one pixel back and forth so each event is a real delta.
            inputs[0].input.mi.dx = new LONG((i % 2 == 0) ? 1 : -1);
            inputs[0].input.mi.dy = new LONG(0);
            inputs[0].input.mi.mouseData = new DWORD(0);
            inputs[0].input.mi.time = new DWORD(0);
            inputs[0].input.mi.dwExtraInfo = new ULONG_PTR(0);
            inputs[0].input.mi.dwFlags = new DWORD(MOUSEEVENTF_MOVE);
            inputs[0].write();
            User32.INSTANCE.SendInput(new DWORD(1), inputs, inputs[0].size());
        }
    }

    /** Synthesizes a bare {@code Alt} press+release via {@code SendInput}. */
    static void tapAlt() {
        INPUT[] inputs = (INPUT[]) new INPUT().toArray(2);
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].type = new DWORD(INPUT.INPUT_KEYBOARD);
            inputs[i].input.setType("ki");
            inputs[i].input.ki.wVk = new com.sun.jna.platform.win32.WinDef.WORD(VK_MENU);
            inputs[i].input.ki.wScan = new com.sun.jna.platform.win32.WinDef.WORD(0);
            inputs[i].input.ki.time = new DWORD(0);
            inputs[i].input.ki.dwExtraInfo = new ULONG_PTR(0);
            inputs[i].input.ki.dwFlags = new DWORD(i == 0 ? 0 : WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
            inputs[i].write();
        }
        User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size());
    }

    static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }
}
