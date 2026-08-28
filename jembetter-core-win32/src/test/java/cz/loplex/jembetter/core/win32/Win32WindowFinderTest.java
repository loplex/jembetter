package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.createVisibleTopLevelWindowAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("windows")
class Win32WindowFinderTest {

    private long hwnd;

    @BeforeEach
    void createWindow() {
        hwnd = createTopLevelWindow("jembetter-core-win32 Win32WindowFinderTest");
    }

    @AfterEach
    void cleanup() {
        destroyWindow(hwnd);
    }

    @Test
    void findsOwnTopLevelWindowByPid() {
        long pid = ProcessHandle.current().pid();

        List<Long> found = Win32WindowFinder.findTopLevelWindowsByPid(pid);

        assertTrue(found.contains(hwnd), "own window not found among this process's top-level windows: " + found);
    }

    @Test
    void findsNothingForAnUnrelatedPid() {
        List<Long> found = Win32WindowFinder.findTopLevelWindowsByPid(-1);

        assertFalse(found.contains(hwnd));
    }

    @Test
    void applicationWindowFilterKeepsVisibleUnownedWindowsAndDropsTheRest() {
        long pid = ProcessHandle.current().pid();
        long visible = createVisibleTopLevelWindowAt("Win32WindowFinderTest visible app window", 0, 0, 40, 40);
        try {
            List<Long> apps = Win32WindowFinder.findApplicationWindowsByPid(pid);

            assertTrue(apps.contains(visible), "visible unowned top-level window missing from the app-window list: " + apps);
            assertFalse(apps.contains(hwnd),
                    "the invisible WS_OVERLAPPEDWINDOW (never shown) leaked into the app-window list: " + apps);
        } finally {
            destroyWindow(visible);
        }
    }
}
