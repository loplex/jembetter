package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createVisibleTopLevelWindowAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32ConfigureWatcher} against a real, borderless HWND —
 * {@code WS_POPUP}, like {@link Win32ReparentWatcherTest}'s siblings, so
 * {@code GetClientRect} matches the size {@link Win32WindowGeometry
 * #moveResize} sets exactly, with no caption/border to subtract.
 */
@Tag("windows")
class Win32ConfigureWatcherTest {

    private Win32ConfigureWatcher watcher;
    private long hwnd;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        destroyWindow(hwnd);
    }

    @Test
    void reportsTheNewSizeAfterAResize() throws InterruptedException {
        hwnd = createVisibleTopLevelWindowAt("Win32ConfigureWatcherTest", 0, 0, 50, 50);
        watcher = new Win32ConfigureWatcher();

        CountDownLatch resized = new CountDownLatch(1);
        AtomicInteger reportedWidth = new AtomicInteger(-1);
        AtomicInteger reportedHeight = new AtomicInteger(-1);
        watcher.watch(hwnd, (width, height) -> {
            reportedWidth.set(width);
            reportedHeight.set(height);
            resized.countDown();
        });

        Win32WindowGeometry.moveResize(hwnd, 0, 0, 200, 150);

        assertTrue(resized.await(5, TimeUnit.SECONDS), "watcher never reported the resize");
        assertEquals(200, reportedWidth.get());
        assertEquals(150, reportedHeight.get());
    }
}
