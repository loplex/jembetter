package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link Win32Reparent} against real HWNDs. Gated on {@code
 * OS.WINDOWS} rather than a Wine-specific condition: a Windows JDK run under
 * Wine self-reports {@code os.name} as Windows, so the same gate covers both
 * a real Windows machine and the Wine-hosted run (see this module's
 * package-info) without ever running on the Linux dev/CI environment this
 * repo is otherwise built in.
 */
@Tag("windows")
class Win32ReparentTest {

    private long parentHwnd;
    private long childHwnd;

    @BeforeEach
    void createWindows() {
        parentHwnd = createTopLevelWindow("jembetter-core-win32 Win32ReparentTest parent");
        childHwnd = createTopLevelWindow("jembetter-core-win32 Win32ReparentTest child");
    }

    @AfterEach
    void destroyWindows() {
        destroyWindow(childHwnd);
        destroyWindow(parentHwnd);
    }

    @Test
    void reparentMovesChildUnderNewParent() {
        Win32Reparent.reparent(childHwnd, parentHwnd, 5, 5);

        assertEquals(parentHwnd, Win32Reparent.parentOf(childHwnd));
    }

    @Test
    void releaseRestoresDesktopParent() {
        Win32Reparent.reparent(childHwnd, parentHwnd, 5, 5);

        Win32Reparent.release(childHwnd, 10, 10);

        assertEquals(0L, Win32Reparent.parentOf(childHwnd));
    }
}
