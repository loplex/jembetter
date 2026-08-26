package cz.loplex.xembed.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static cz.loplex.xembed.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.xembed.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32ReparentWatcher} against real HWNDs. Gated on {@code
 * OS.WINDOWS} the same way this package's other primitive tests are.
 */
@EnabledOnOs(OS.WINDOWS)
class Win32ReparentWatcherTest {

    private Win32ReparentWatcher watcher;
    private long parentHwnd;
    private long childHwnd;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        destroyWindow(childHwnd);
        destroyWindow(parentHwnd);
    }

    @Test
    void reportsANewParentAfterSetParent() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest child");
        watcher = new Win32ReparentWatcher();

        CountDownLatch reparented = new CountDownLatch(1);
        AtomicLong reportedParent = new AtomicLong(-1);
        watcher.watch(childHwnd, newParent -> {
            reportedParent.set(newParent);
            reparented.countDown();
        });

        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);

        assertTrue(reparented.await(5, TimeUnit.SECONDS), "watcher never reported the SetParent");
        assertEquals(parentHwnd, reportedParent.get());
    }

    @Test
    void reportsZeroAfterTheWindowIsDestroyed() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest child");
        watcher = new Win32ReparentWatcher();
        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);

        CountDownLatch detached = new CountDownLatch(1);
        AtomicLong reportedParent = new AtomicLong(-1);
        watcher.watch(childHwnd, newParent -> {
            reportedParent.set(newParent);
            detached.countDown();
        });

        destroyWindow(childHwnd);

        assertTrue(detached.await(5, TimeUnit.SECONDS), "watcher never reported the destroyed window");
        assertEquals(0L, reportedParent.get());
    }
}
