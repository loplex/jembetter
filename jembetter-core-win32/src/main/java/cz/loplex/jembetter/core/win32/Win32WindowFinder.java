package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a process's own top-level window(s) by {@code EnumWindows} +
 * {@code GetWindowThreadProcessId}, filtered by pid. Mirrors {@code
 * jembetter-core-x11}'s {@code WindowFinder}, without an X11 {@code WM_CLASS}
 * equivalent — {@code EnumWindows} only visits top-level windows in the
 * first place, so no separate "narrow to top-level" step is needed.
 */
public final class Win32WindowFinder {

    // Not exposed by JNA's WinUser; values per winuser.h.
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_APPWINDOW = 0x00040000;

    private Win32WindowFinder() {
    }

    public static List<Long> findTopLevelWindowsByPid(long pid) {
        List<Long> matches = new ArrayList<>();
        IntByReference ownerPid = new IntByReference();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, ownerPid);
            if (ownerPid.getValue() == pid) {
                matches.add(Pointer.nativeValue(hwnd.getPointer()));
            }
            return true; // keep enumerating
        }, null);
        return matches;
    }

    /**
     * Narrows {@link #findTopLevelWindowsByPid} to the process's genuine
     * application windows — the Win32 analogue of X11's {@code
     * _NET_CLIENT_LIST}, which {@code jembetter-core-x11}'s {@code WindowFinder}
     * relies on to skip toolkit-internal windows. {@code EnumWindows} has no such filter of its
     * own, so a Swing process shows up here as both its real frame <em>and</em>
     * the invisible {@code SunAwtToolkit} helper window (the latter is
     * message-only on a real Windows JDK and thus invisible to {@code
     * EnumWindows}, but a plain hidden top-level window under Wine).
     *
     * <p>Uses the standard alt-tab-window heuristic (see Raymond Chen,
     * "Which windows appear in the Alt+Tab list?"): visible, unowned, and not
     * a tool window unless it explicitly opts back in with {@code
     * WS_EX_APPWINDOW}.
     */
    public static List<Long> findApplicationWindowsByPid(long pid) {
        List<Long> matches = new ArrayList<>();
        for (long hwnd : findTopLevelWindowsByPid(pid)) {
            if (isApplicationWindow(new HWND(new Pointer(hwnd)))) {
                matches.add(hwnd);
            }
        }
        return matches;
    }

    private static boolean isApplicationWindow(HWND hwnd) {
        if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
            return false;
        }
        if (User32.INSTANCE.GetWindow(hwnd, new DWORD(WinUser.GW_OWNER)) != null) {
            return false;
        }
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if ((exStyle & WS_EX_APPWINDOW) != 0) {
            return true;
        }
        return (exStyle & WS_EX_TOOLWINDOW) == 0;
    }

    /** One-line {@code hwnd / visible / class / title / rect} dump, for diagnostics. */
    public static String describeWindow(long hwnd) {
        HWND handle = new HWND(new Pointer(hwnd));
        char[] title = new char[256];
        int titleLen = User32.INSTANCE.GetWindowText(handle, title, title.length);
        char[] className = new char[256];
        int classLen = User32.INSTANCE.GetClassName(handle, className, className.length);
        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(handle, rect);
        return "hwnd=0x" + Long.toHexString(hwnd)
                + " visible=" + User32.INSTANCE.IsWindowVisible(handle)
                + " class='" + new String(className, 0, Math.max(classLen, 0)) + "'"
                + " title='" + new String(title, 0, Math.max(titleLen, 0)) + "'"
                + " rect=[" + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "]";
    }

    /** The pid of the process that owns {@code hwnd}, via {@code GetWindowThreadProcessId}. */
    public static long pidOfWindow(long hwnd) {
        IntByReference ownerPid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(new HWND(new Pointer(hwnd)), ownerPid);
        return Integer.toUnsignedLong(ownerPid.getValue());
    }
}
