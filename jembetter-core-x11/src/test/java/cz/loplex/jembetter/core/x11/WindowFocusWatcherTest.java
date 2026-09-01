package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowFocusWatcherTest {

    @Test
    void reportsFocusGainedThenLostWhenAnotherConnectionMovesInputFocus() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowFocusWatcher watcher = new WindowFocusWatcher()) {

            long watched = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);
            long elsewhere = RawWindow.createOverrideRedirect(clientDisplay, 20, 20, 10, 10);
            waitUntilMapped(clientDisplay, watched);
            waitUntilMapped(clientDisplay, elsewhere);

            try {
                BlockingQueue<Boolean> reported = new ArrayBlockingQueue<>(8);
                watcher.watch(watched, reported::add);

                // Stands in for a host pointing X input focus at an embedded
                // client window (InputFocus.set) from its own connection.
                try (X11Display hostDisplay = X11Display.open(null)) {
                    InputFocus.set(hostDisplay, watched);
                    assertEquals(Boolean.TRUE, reported.poll(5, TimeUnit.SECONDS),
                            "focus gained was never reported");

                    InputFocus.set(hostDisplay, elsewhere);
                    assertEquals(Boolean.FALSE, reported.poll(5, TimeUnit.SECONDS),
                            "focus lost was never reported");
                }
            } finally {
                RawWindow.destroy(clientDisplay, watched);
                RawWindow.destroy(clientDisplay, elsewhere);
            }
        }
    }

    @Test
    void doesNotReportAgainWhileFocusStaysUnchanged() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowFocusWatcher watcher = new WindowFocusWatcher()) {

            long watched = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);
            waitUntilMapped(clientDisplay, watched);

            try {
                BlockingQueue<Boolean> reported = new ArrayBlockingQueue<>(8);
                watcher.watch(watched, reported::add);

                try (X11Display hostDisplay = X11Display.open(null)) {
                    InputFocus.set(hostDisplay, watched);
                    assertEquals(Boolean.TRUE, reported.poll(5, TimeUnit.SECONDS), "focus gained was never reported");

                    // Re-asserting the same focus generates fresh FocusIn
                    // events, but the state the caller cares about hasn't
                    // changed, so nothing new should be reported.
                    InputFocus.set(hostDisplay, watched);
                    InputFocus.set(hostDisplay, watched);
                    assertEquals(null, reported.poll(1, TimeUnit.SECONDS),
                            "a redundant focus-gained was reported");
                }
            } finally {
                RawWindow.destroy(clientDisplay, watched);
            }
        }
    }

    @Test
    void ignoresTheFocusOutInPairAGrabBracketsTheWindowWith() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowFocusWatcher watcher = new WindowFocusWatcher()) {

            long watched = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);
            waitUntilMapped(clientDisplay, watched);

            try {
                BlockingQueue<Boolean> reported = new ArrayBlockingQueue<>(8);
                watcher.watch(watched, reported::add);

                try (X11Display hostDisplay = X11Display.open(null)) {
                    InputFocus.set(hostDisplay, watched);
                    assertEquals(Boolean.TRUE, reported.poll(5, TimeUnit.SECONDS), "focus gained was never reported");

                    // A keyboard grab on the focused window brackets it with
                    // a FocusOut(NotifyGrab)/FocusIn(NotifyUngrab) pair — the
                    // same shape the passive XGrabButton behind click-to-focus
                    // produces. The window never actually stopped being the
                    // focus, so nothing should be reported.
                    synchronized (X11Display.GLOBAL_LOCK) {
                        X11Ext.INSTANCE.XGrabKeyboard(hostDisplay.raw(), new Window(watched), 1, X11Ext.GrabModeAsync,
                                X11Ext.GrabModeAsync, new NativeLong(X11Ext.CurrentTime));
                        X11Ext.INSTANCE.XUngrabKeyboard(hostDisplay.raw(), new NativeLong(X11Ext.CurrentTime));
                        X11Ext.INSTANCE.XFlush(hostDisplay.raw());
                    }

                    assertEquals(null, reported.poll(1, TimeUnit.SECONDS),
                            "a grab's bracketing focus out/in pair was reported as a real focus change");
                }
            } finally {
                RawWindow.destroy(clientDisplay, watched);
            }
        }
    }

    @Test
    void unwatchStopsFurtherCallbacks() throws InterruptedException {
        try (X11Display clientDisplay = X11Display.open(null);
                WindowFocusWatcher watcher = new WindowFocusWatcher()) {

            long watched = RawWindow.createOverrideRedirect(clientDisplay, 0, 0, 10, 10);
            waitUntilMapped(clientDisplay, watched);

            try {
                CountDownLatch focused = new CountDownLatch(1);
                watcher.watch(watched, gained -> focused.countDown());
                watcher.unwatch(watched);

                try (X11Display hostDisplay = X11Display.open(null)) {
                    InputFocus.set(hostDisplay, watched);
                }

                assertTrue(!focused.await(1, TimeUnit.SECONDS), "callback fired after unwatch");
            } finally {
                RawWindow.destroy(clientDisplay, watched);
            }
        }
    }

    private static void waitUntilMapped(X11Display display, long windowId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (WindowTree.isMapped(display, windowId)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("window " + windowId + " never became mapped");
    }
}
