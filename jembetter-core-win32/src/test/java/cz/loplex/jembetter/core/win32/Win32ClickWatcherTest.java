package cz.loplex.jembetter.core.win32;

import com.sun.jna.platform.win32.WinDef.RECT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.clickAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.createVisibleTopLevelWindowAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.rectOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32ClickWatcher} against a real HWND and real injected
 * mouse input: that the {@code WH_MOUSE_LL} hook installs, its {@code
 * LowLevelMouseProc} callback marshals, the message pump dispatches, the
 * screen-coordinate hit-test works, and {@code close()} unhooks cleanly.
 * Gated on {@code OS.WINDOWS} like this package's other primitive tests, so
 * it also runs under {@code .mvn/win32-wine-smoketest}.
 */
@EnabledOnOs(OS.WINDOWS)
class Win32ClickWatcherTest {

    private Win32ClickWatcher watcher;
    private long hwnd;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        destroyWindow(hwnd);
    }

    @Test
    void clickInsideAWatchedWindowInvokesTheCallback() throws InterruptedException {
        hwnd = createVisibleTopLevelWindowAt("Win32ClickWatcherTest inside", 120, 120, 300, 200);
        watcher = new Win32ClickWatcher();

        CountDownLatch clicked = new CountDownLatch(1);
        watcher.watch(hwnd, clicked::countDown);

        RECT rect = rectOf(hwnd);
        clickAt((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2);

        assertTrue(clicked.await(3, TimeUnit.SECONDS),
                "a click inside the watched rect never reached the callback");
    }

    @Test
    void clickOutsideAWatchedWindowDoesNotInvokeTheCallback() throws InterruptedException {
        hwnd = createVisibleTopLevelWindowAt("Win32ClickWatcherTest outside", 120, 120, 200, 150);
        watcher = new Win32ClickWatcher();

        CountDownLatch clicked = new CountDownLatch(1);
        watcher.watch(hwnd, clicked::countDown);

        RECT rect = rectOf(hwnd);
        clickAt(rect.right + 200, rect.bottom + 200);

        assertFalse(clicked.await(1, TimeUnit.SECONDS),
                "a click outside the watched rect was wrongly delivered to the callback");
    }

    @Test
    void closeUnhooksWithoutThrowing() {
        assertDoesNotThrow(() -> new Win32ClickWatcher().close());
    }
}
