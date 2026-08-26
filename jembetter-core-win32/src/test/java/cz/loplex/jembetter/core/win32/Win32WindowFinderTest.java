package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
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
}
