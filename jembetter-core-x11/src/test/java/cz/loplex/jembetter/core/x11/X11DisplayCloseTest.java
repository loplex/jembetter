package cz.loplex.jembetter.core.x11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link X11Display#ifOpen}: a native call must not
 * start against a connection that has already been closed, however narrowly
 * it loses the race.
 *
 * <p>The crash this guards against is a JVM-level {@code SIGSEGV} inside
 * Xlib — an AWT focus callback reaching {@code XInternAtom} on a {@code
 * Display*} that {@code XCloseDisplay} had freed while the callback was
 * parked on {@link X11Display#GLOBAL_LOCK}. Unregistering the callback
 * before closing does not prevent it, because a callback that has already
 * started keeps running.
 *
 * <p>These tests deliberately do not make a native call from the racing
 * thread: one that lost the race would take the whole JVM down rather than
 * fail an assertion, which is exactly the outcome being designed away. They
 * assert the observable invariant instead — that the action does not run —
 * and hold {@code GLOBAL_LOCK} themselves to force the losing interleaving
 * every time, rather than hoping for it.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class X11DisplayCloseTest {

    @Test
    void ifOpenRunsTheActionAgainstThisConnectionWhileItIsOpen() {
        AtomicReference<Object> handedTo = new AtomicReference<>();
        try (X11Display display = X11Display.open(null)) {
            display.ifOpen(handedTo::set);
            assertSame(display.raw(), handedTo.get(), "ifOpen handed the action a different Display than raw()");
        }
    }

    @Test
    void ifOpenAfterCloseDoesNotRunTheAction() {
        X11Display display = X11Display.open(null);
        display.close();

        AtomicBoolean ran = new AtomicBoolean(false);
        display.ifOpen(raw -> ran.set(true));

        assertFalse(ran.get(), "ifOpen called into a connection that was already closed");
    }

    @Test
    void anIfOpenParkedOnTheLockWhenTheConnectionClosesNeverRuns() throws InterruptedException {
        X11Display display = X11Display.open(null);

        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch reachedIfOpen = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            reachedIfOpen.countDown();
            display.ifOpen(raw -> ran.set(true));
        }, "x11-display-close-race-caller");

        // Holding the lock here is what makes the race deterministic: the
        // caller cannot get past ifOpen's monitor until close() has run and
        // this block has been left, which is precisely the interleaving that
        // used to reach a freed Display*.
        synchronized (X11Display.GLOBAL_LOCK) {
            caller.start();
            assertTrue(reachedIfOpen.await(5, TimeUnit.SECONDS), "the calling thread never reached ifOpen");
            awaitBlockedOnTheLock(caller);
            display.close();
        }

        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(caller.isAlive(), "the calling thread never left ifOpen");
        assertFalse(ran.get(), "an ifOpen action ran after the connection it was handed had been closed");
    }

    @Test
    void ifOpenWithAFallbackReturnsItRatherThanCallingIntoAClosedConnection() {
        X11Display open = X11Display.open(null);
        assertEquals("ran", open.ifOpen(raw -> "ran", "connection closed"));
        open.close();

        assertEquals("connection closed", open.ifOpen(raw -> "ran", "connection closed"));
    }

    @Test
    void requireOpenThrowsRatherThanCallingIntoAClosedConnection() {
        X11Display open = X11Display.open(null);
        assertEquals("ran", open.requireOpen(raw -> "ran"));
        open.close();

        assertThrows(IllegalStateException.class, () -> open.requireOpen(raw -> "ran"));
    }

    /**
     * The package-wide contract, rather than the accessor in isolation: a
     * command is skipped and a query fails, both against a connection that
     * is already gone.
     *
     * <p>Only the query half of this is a strict assertion. A regression on
     * the command half would call into freed memory, and what that does is
     * undefined — usually a crash that takes the whole fork down, sometimes
     * nothing at all. It is here to pin the intended behavior, not as the
     * detector; {@link #anIfOpenParkedOnTheLockWhenTheConnectionClosesNeverRuns}
     * is that.
     */
    @Test
    void afterCloseACommandIsSkippedAndAQueryFails() {
        X11Display display = X11Display.open(null);
        long windowId = RawWindow.createOverrideRedirect(display, 0, 0, 10, 10);
        display.close();

        WindowGeometry.raise(display, windowId);

        assertThrows(IllegalStateException.class, () -> WindowTree.parentOf(display, windowId));
    }

    @Test
    void closingTwiceClosesTheConnectionOnlyOnce() {
        X11Display display = X11Display.open(null);

        display.close();
        // A second XCloseDisplay on the same Display* is a double free, i.e.
        // a JVM crash rather than anything catchable, so reaching the
        // assertion below at all is most of what this test checks.
        display.close();

        AtomicBoolean ran = new AtomicBoolean(false);
        display.ifOpen(raw -> ran.set(true));
        assertFalse(ran.get(), "a twice-closed connection still reported itself open");
    }

    private static void awaitBlockedOnTheLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline,
                    "the calling thread never blocked on GLOBAL_LOCK (state: " + thread.getState() + ")");
            Thread.sleep(1);
        }
    }
}
