package cz.loplex.xembed.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a process's own top-level window(s) by {@code EnumWindows} +
 * {@code GetWindowThreadProcessId}, filtered by pid. Mirrors {@code
 * xembed-core}'s {@code WindowFinder}, without an X11 {@code WM_CLASS}
 * equivalent — {@code EnumWindows} only visits top-level windows in the
 * first place, so no separate "narrow to top-level" step is needed.
 */
public final class Win32WindowFinder {

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
}
