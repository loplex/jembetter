package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Only checks that {@link Win32Focus#set} reaches {@code SetFocus} without
 * throwing — see {@link Win32Focus}'s Javadoc for why this deliberately
 * stops short of asserting focus actually moved: Windows' foreground-lock
 * restriction can make {@code SetFocus} a silent no-op depending on which
 * process currently owns the foreground, and Wine doesn't faithfully
 * replicate that policy either way, so a stronger assertion here would be
 * asserting something this test environment can't actually confirm.
 */
@Tag("windows")
class Win32FocusTest {

    private long hwnd;

    @BeforeEach
    void createWindow() {
        hwnd = createTopLevelWindow("jembetter-core-win32 Win32FocusTest");
    }

    @AfterEach
    void cleanup() {
        destroyWindow(hwnd);
    }

    @Test
    void setReachesRealUser32WithoutThrowing() {
        assertDoesNotThrow(() -> Win32Focus.set(hwnd));
    }
}
