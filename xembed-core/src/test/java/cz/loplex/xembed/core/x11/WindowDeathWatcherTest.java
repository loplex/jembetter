package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowDeathWatcherTest {

    @Test
    void invokesCallbackWhenWatchedWindowIsDestroyed() throws InterruptedException {
        try (X11Display display = X11Display.open(null);
                WindowDeathWatcher watcher = new WindowDeathWatcher()) {

            Window window = X11Ext.INSTANCE.XCreateSimpleWindow(display.raw(), display.defaultRootWindow(), 0, 0, 10,
                    10, 0, 0, 0);
            // The watcher selects input on this window from a *different*
            // connection; without a sync here, its XSelectInput can reach
            // the server before this connection's XCreateSimpleWindow does,
            // which the server reports as BadWindow.
            X11Ext.INSTANCE.XSync(display.raw(), false);

            CountDownLatch destroyed = new CountDownLatch(1);
            AtomicLong reportedWindowId = new AtomicLong(-1);
            watcher.watch(window.longValue(), id -> {
                reportedWindowId.set(id);
                destroyed.countDown();
            });

            X11Ext.INSTANCE.XDestroyWindow(display.raw(), window);
            X11Ext.INSTANCE.XFlush(display.raw());

            assertTrue(destroyed.await(5, TimeUnit.SECONDS), "DestroyNotify callback was never invoked");
            assertEquals(window.longValue(), reportedWindowId.get());
        }
    }
}
