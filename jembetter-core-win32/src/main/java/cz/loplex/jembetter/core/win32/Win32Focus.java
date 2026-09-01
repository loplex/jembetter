package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Wrapper around {@code SetFocus}/{@code SetForegroundWindow} operating on
 * a raw HWND value. Mirrors {@code jembetter-core-x11}'s {@code InputFocus}.
 *
 * <p><b>Foreground-lock, confirmed on a real Windows machine:</b> a
 * 2026-08-26 real-machine spike (see this module's package-info)
 * confirmed that a plain {@code SetFocus}/{@code SetForegroundWindow} call
 * from a process that isn't the current foreground process is a silent
 * no-op — {@code SetForegroundWindow} can even return {@code true} without
 * the foreground actually changing.
 *
 * <p>A follow-up real-machine spike (2026-08-28, {@code windows-latest} =
 * Windows Server 2025) then compared candidate workarounds head to head
 * from a genuinely non-foreground process:
 * <ul>
 *   <li>plain {@code SetForegroundWindow}, even retried with verification —
 *       <b>does not</b> move the foreground;</li>
 *   <li>{@code AttachThreadInput} to the current foreground window's thread,
 *       then {@code SetForegroundWindow}/{@code BringWindowToTop}/{@code
 *       SetFocus}, then detach — <b>works</b> (attached threads share
 *       foreground state);</li>
 *   <li>a synthetic {@code Alt} keypress and {@code
 *       SPI_SETFOREGROUNDLOCKTIMEOUT}=0 also work, but the first injects
 *       system-wide input and the second mutates a global system setting —
 *       both worse for a library than the {@code AttachThreadInput} path.</li>
 * </ul>
 * {@link #set} therefore verifies the result with {@code GetForegroundWindow}
 * rather than trusting {@code SetForegroundWindow}'s return value, and falls
 * back to the {@code AttachThreadInput} sequence when the plain call didn't
 * take effect.
 */
public final class Win32Focus {

    private Win32Focus() {
    }

    /** Points Win32 keyboard input focus at {@code hwnd}. */
    public static void set(long hwnd) {
        HWND target = new HWND(new Pointer(hwnd));

        // SetForegroundWindow's BOOL return is not trustworthy from a
        // non-foreground process - on real Windows it returns true without
        // the foreground actually moving (see this class's Javadoc), so
        // verify against GetForegroundWindow rather than the return value.
        User32.INSTANCE.SetForegroundWindow(target);
        if (isForeground(hwnd)) {
            return;
        }

        // The plain call didn't take effect. Attach this thread's input
        // queue to the current foreground window's thread and retry; Windows
        // treats attached threads as sharing foreground state. The 2026-08-28
        // spike confirmed this path moves the foreground where the plain call
        // (even retried) does not.
        HWND currentForeground = User32.INSTANCE.GetForegroundWindow();
        if (currentForeground == null) {
            User32.INSTANCE.SetFocus(target);
            return;
        }

        IntByReference foregroundPid = new IntByReference();
        int foregroundTid = User32.INSTANCE.GetWindowThreadProcessId(currentForeground, foregroundPid);
        int ourTid = Kernel32.INSTANCE.GetCurrentThreadId();
        if (foregroundTid == 0 || foregroundTid == ourTid) {
            User32.INSTANCE.SetFocus(target);
            return;
        }

        DWORD attachFrom = new DWORD(ourTid);
        DWORD attachTo = new DWORD(foregroundTid);
        User32.INSTANCE.AttachThreadInput(attachFrom, attachTo, true);
        try {
            User32.INSTANCE.SetForegroundWindow(target);
            User32.INSTANCE.BringWindowToTop(target);
            User32.INSTANCE.SetFocus(target);
        } finally {
            User32.INSTANCE.AttachThreadInput(attachFrom, attachTo, false);
        }
    }

    private static boolean isForeground(long hwnd) {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        return foreground != null && Pointer.nativeValue(foreground.getPointer()) == hwnd;
    }
}
