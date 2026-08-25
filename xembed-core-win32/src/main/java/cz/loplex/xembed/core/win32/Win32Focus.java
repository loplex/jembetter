package cz.loplex.xembed.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

/**
 * Wrapper around {@code SetFocus}/{@code SetForegroundWindow} operating on
 * a raw HWND value. Mirrors {@code xembed-core}'s {@code InputFocus}.
 *
 * <p><b>Foreground-lock, confirmed on a real Windows machine:</b> a
 * 2026-08-26 real-machine spike (see this module's package-info)
 * confirmed that a plain {@code SetFocus}/{@code SetForegroundWindow} call
 * from a process that isn't the current foreground process is a silent
 * no-op — {@code SetForegroundWindow} can even return {@code true} without
 * the foreground actually changing. The spike also confirmed one known
 * workaround works: {@code AllowSetForegroundWindow}, called by whichever
 * process currently holds the foreground, targeting this process's pid.
 * That requires cooperation from a process we don't control, though, so
 * {@link #set} instead uses {@code AttachThreadInput} to attach this
 * thread's input queue to the current foreground window's thread before
 * retrying — attached threads share foreground state, which sidesteps the
 * restriction without needing that cooperation. <b>This specific fallback
 * is an implementation choice against documented Win32 semantics, not
 * itself something the spike exercised</b> — the spike only tried the
 * {@code AllowSetForegroundWindow} path, not {@code AttachThreadInput}.
 */
public final class Win32Focus {

    private Win32Focus() {
    }

    /** Points Win32 keyboard input focus at {@code hwnd}. */
    public static void set(long hwnd) {
        HWND target = new HWND(new Pointer(hwnd));
        if (User32.INSTANCE.SetForegroundWindow(target)) {
            return;
        }

        // SetForegroundWindow either returned false outright, or returned
        // true without actually moving the foreground (both confirmed on
        // real Windows - see this class's Javadoc). Attach this thread's
        // input queue to the current foreground window's thread and retry;
        // Windows treats attached threads as sharing foreground state.
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
            User32.INSTANCE.SetFocus(target);
        } finally {
            User32.INSTANCE.AttachThreadInput(attachFrom, attachTo, false);
        }
    }
}
