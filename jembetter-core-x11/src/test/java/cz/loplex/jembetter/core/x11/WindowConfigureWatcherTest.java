package cz.loplex.jembetter.core.x11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowConfigureWatcherTest {

    @Test
    void invokesCallbackWithNewSizeWhenAnotherConnectionResizesTheWindow() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowConfigureWatcher watcher = new WindowConfigureWatcher()) {

            long clientWindow = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);

            try {
                CountDownLatch resized = new CountDownLatch(1);
                AtomicInteger reportedWidth = new AtomicInteger(-1);
                AtomicInteger reportedHeight = new AtomicInteger(-1);
                watcher.watch(clientWindow, (width, height) -> {
                    reportedWidth.set(width);
                    reportedHeight.set(height);
                    resized.countDown();
                });

                // Stands in for a host resizing an embedded window it doesn't
                // own via a separate connection (e.g. WindowGeometry#moveResize
                // following a host-side Canvas resize).
                try (X11Display hostDisplay = X11Display.open(null)) {
                    WindowGeometry.moveResize(hostDisplay, clientWindow, 0, 0, 42, 24);
                }

                assertTrue(resized.await(5, TimeUnit.SECONDS), "ConfigureNotify callback was never invoked");
                assertEquals(42, reportedWidth.get());
                assertEquals(24, reportedHeight.get());
            } finally {
                RawWindow.destroy(clientDisplay, clientWindow);
            }
        }
    }

    @Test
    void unwatchStopsFurtherCallbacks() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowConfigureWatcher watcher = new WindowConfigureWatcher()) {

            long clientWindow = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);

            try {
                CountDownLatch resized = new CountDownLatch(1);
                watcher.watch(clientWindow, (width, height) -> resized.countDown());
                watcher.unwatch(clientWindow);

                try (X11Display hostDisplay = X11Display.open(null)) {
                    WindowGeometry.moveResize(hostDisplay, clientWindow, 0, 0, 42, 24);
                }

                assertTrue(!resized.await(1, TimeUnit.SECONDS), "callback fired after unwatch");
            } finally {
                RawWindow.destroy(clientDisplay, clientWindow);
            }
        }
    }
}
