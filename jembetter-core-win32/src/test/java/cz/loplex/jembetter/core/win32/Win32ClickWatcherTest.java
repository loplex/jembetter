package cz.loplex.jembetter.core.win32;

import com.sun.jna.platform.win32.WinDef.RECT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.clickAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.createVisibleTopLevelWindowAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.rectOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32ClickWatcher} against a real HWND and real injected
 * mouse input: that the {@code WH_MOUSE_LL} hook installs, its {@code
 * LowLevelMouseProc} callback marshals, the message pump dispatches, the
 * screen-coordinate hit-test works, and {@code close()} unhooks cleanly.
 * {@code @Tag("windows")} like this package's other primitive tests, so it
 * runs on real Windows in {@code windows-ci.yml} and under Wine via {@code
 * mvn test}'s {@code windows-tests-on-linux} execution.
 */
@Tag("windows")
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

    // Was @Tag("wine-incompatible") until 2026-09-13, on the grounds that Wine's
    // WH_MOUSE_LL emulation never delivered a SendInput-synthesized click to the hook.
    // wine-staging 11.16 does: run under the Wine fork with the exclusion lifted
    // (`mvn test -Dwine.excludedGroups=`) this passed every time across repeated runs.
    // If it starts failing under a Wine that regresses, re-measure before re-tagging -
    // the tag is a claim about a specific Wine, not a permanent property.
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

    /**
     * A {@code WH_MOUSE_LL} proc that overruns {@code LowLevelHooksTimeout}
     * too often is silently unhooked by Windows; {@code Win32ClickWatcher}
     * offloads callback work to a dispatch thread to stay under that budget.
     * A burst of clicks should nearly all still reach the callback.
     *
     * <p>Was {@code @Tag("wine-incompatible")} until 2026-09-13 on the same
     * since-disproved premise as the test above — that Wine delivers no
     * {@code SendInput} click to the hook at all.
     */
    @Test
    void theHookSurvivesABurstOfClicks() throws InterruptedException {
        hwnd = createVisibleTopLevelWindowAt("Win32ClickWatcherTest burst", 120, 120, 300, 200);
        watcher = new Win32ClickWatcher();

        AtomicInteger hits = new AtomicInteger();
        watcher.watch(hwnd, hits::incrementAndGet);

        RECT rect = rectOf(hwnd);
        int cx = (rect.left + rect.right) / 2;
        int cy = (rect.top + rect.bottom) / 2;
        int burst = 30;
        for (int i = 0; i < burst; i++) {
            clickAt(cx, cy);
            Thread.sleep(15);
        }

        long deadline = System.currentTimeMillis() + 3000;
        while (hits.get() < burst && System.currentTimeMillis() < deadline) {
            //noinspection BusyWait
            Thread.sleep(50);
        }

        assertTrue(hits.get() >= (int) (burst * 0.8),
                "only " + hits.get() + "/" + burst + " clicks reached the callback - the "
                        + "WH_MOUSE_LL hook was likely dropped for overrunning LowLevelHooksTimeout");
    }

    /**
     * A budget that runs out is reported as a budget, not as a failed hook.
     *
     * <p>The two want opposite responses — one means wait longer, the other
     * means the hook is not going to install — and until 2026-09-15 they
     * produced the same {@link IllegalStateException} from the same branch.
     * That mattered in practice: the 5 s default failed 2 of 4 full runs of
     * {@code jembetter-host} on a machine ninety minutes into running test
     * suites, and read as a defect rather than as a slow machine.
     *
     * <p>Skipped rather than failed if the hook installs inside a budget this
     * small, which is possible if unlikely: this asserts on which message a
     * timeout produces, so a run that did not time out has nothing to say.
     */
    @Test
    void aBudgetThatRunsOutIsReportedAsABudgetRatherThanAFailedHook() {
        IllegalStateException thrown = null;
        try {
            new Win32ClickWatcher(Duration.ZERO).close();
        } catch (IllegalStateException e) {
            thrown = e;
        }
        assumeTrue(thrown != null, "the hook installed within a zero budget; nothing to assert on");
        String message = thrown.getMessage();
        assertTrue(message.contains("did not report its hook"),
                () -> "a budget running out should say so; got: " + message);
        assertFalse(message.contains("failed with error"),
                () -> "a budget running out was reported as SetWindowsHookEx failing; got: " + message);
    }

    /** The install budget is an argument, so it is rejected at the call that got it wrong. */
    @Test
    void aNullInstallTimeoutIsRejected() {
        assertThrows(NullPointerException.class, () -> new Win32ClickWatcher(null));
    }

    /** A generous budget behaves exactly as the no-argument constructor does. */
    @Test
    void anExplicitBudgetInstallsTheHookTheSameWay() {
        assertDoesNotThrow(() -> new Win32ClickWatcher(Duration.ofSeconds(30)).close());
    }
}
