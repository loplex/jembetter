package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

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

    /**
     * Regression coverage for the transient that
     * {@code Win32ReparentWatcherTest.reportsANewParentAfterSetParent} caught
     * on CI at 3-4 iterations in 10 and could only catch by luck: a window
     * carrying {@code WS_CHILD} with no parent of its own is a child of the
     * desktop as far as {@code GetParent} is concerned, and reading that
     * literally makes {@code reparent}'s own intermediate state look like a
     * host embedding the window. Reproduced here without the race, by styling
     * a window and not reparenting it.
     *
     * <p>The first assertion is the premise, not the behaviour: if Windows
     * ever stopped reporting the desktop here, the second assertion would pass
     * for a reason that has nothing to do with the code it covers.
     */
    @Test
    void aWindowStyledAsAChildWithNoParentOfItsOwnReadsAsUnparented() {
        Win32TestWindows.addChildStyle(childHwnd);

        assertEquals(Win32TestWindows.desktopWindow(), Win32TestWindows.rawParentOf(childHwnd),
                "premise: Windows reports the desktop as the parent of a styled-but-unparented child");
        assertEquals(0L, Win32Reparent.parentOf(childHwnd));
    }

    @Test
    void releaseRestoresDesktopParent() {
        Win32Reparent.reparent(childHwnd, parentHwnd, 5, 5);

        Win32Reparent.release(childHwnd, 10, 10);

        assertEquals(0L, Win32Reparent.parentOf(childHwnd));
    }
}
