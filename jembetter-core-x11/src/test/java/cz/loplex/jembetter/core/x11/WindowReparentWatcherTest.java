package cz.loplex.jembetter.core.x11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowReparentWatcherTest {

    @Test
    void invokesCallbackWithRootWindowWhenEmbedderConnectionCloses() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowReparentWatcher watcher = new WindowReparentWatcher()) {

            long clientWindow = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);

            // Stands in for a host embedding this window before dying: a
            // separate connection reparents it under one of its own windows
            // and adds it to its save-set, the same way
            // Reparenting#reparent does for a real embed.
            X11Display hostDisplay = X11Display.open(null);
            long embedderWindow = RawWindow.createOverrideRedirect(hostDisplay, 0, 0, 20, 20);
            Reparenting.reparent(hostDisplay, clientWindow, embedderWindow, 0, 0);

            try {
                // Watching only starts now, after the embed, so the only
                // ReparentNotify the watcher observes is the save-set one
                // triggered by the host connection closing below.
                CountDownLatch reparented = new CountDownLatch(1);
                AtomicLong reportedParent = new AtomicLong(-1);
                watcher.watch(clientWindow, parent -> {
                    reportedParent.set(parent);
                    reparented.countDown();
                });

                // Simulates the host process dying: closing its connection
                // triggers save-set processing for windows it reparented in.
                hostDisplay.close();

                assertTrue(reparented.await(5, TimeUnit.SECONDS), "ReparentNotify callback was never invoked");
                assertEquals(clientDisplay.defaultRootWindow().longValue(), reportedParent.get());
            } finally {
                RawWindow.destroy(clientDisplay, clientWindow);
            }
        }
    }
}
