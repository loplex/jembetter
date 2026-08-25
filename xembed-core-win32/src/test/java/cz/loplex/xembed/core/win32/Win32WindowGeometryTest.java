package cz.loplex.xembed.core.win32;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.RECT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static cz.loplex.xembed.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.xembed.core.win32.Win32TestWindows.destroyWindow;
import static cz.loplex.xembed.core.win32.Win32TestWindows.toHwnd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class Win32WindowGeometryTest {

    private long parentHwnd;
    private long hwnd;

    @BeforeEach
    void createWindow() {
        parentHwnd = createTopLevelWindow("xembed-core-win32 Win32WindowGeometryTest parent");
        hwnd = createTopLevelWindow("xembed-core-win32 Win32WindowGeometryTest");
    }

    @AfterEach
    void cleanup() {
        destroyWindow(hwnd);
        destroyWindow(parentHwnd);
    }

    @Test
    void moveResizeChangesTheWindowRect() {
        // A still-top-level WS_THICKFRAME window has an OS-enforced minimum
        // track size that silently clamps MoveWindow's requested size -
        // real Win32 behavior (SetWindowPos/MoveWindow honor
        // WM_GETMINMAXINFO's ptMinTrackSize for any WS_THICKFRAME window,
        // not just interactive drag-resizes), confirmed by this smoke test
        // itself failing here before this reparent call was added. Go
        // through Win32Reparent first, exactly like real usage always does,
        // which clears WS_THICKFRAME along with the other top-level style
        // bits and removes that clamp.
        Win32Reparent.reparent(hwnd, parentHwnd, 0, 0);

        Win32WindowGeometry.moveResize(hwnd, 20, 30, 100, 80);

        RECT rect = new RECT();
        User32.INSTANCE.GetWindowRect(toHwnd(hwnd), rect);
        assertEquals(100, rect.right - rect.left);
        assertEquals(80, rect.bottom - rect.top);
    }

    @Test
    void setMappedTogglesVisibility() {
        Win32WindowGeometry.setMapped(hwnd, true);
        assertTrue(User32.INSTANCE.IsWindowVisible(toHwnd(hwnd)));

        Win32WindowGeometry.setMapped(hwnd, false);
        assertFalse(User32.INSTANCE.IsWindowVisible(toHwnd(hwnd)));
    }
}
