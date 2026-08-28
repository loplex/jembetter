package cz.loplex.jembetter.core.win32;

import com.sun.jna.platform.win32.WinDef.RECT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Wine's WH_MOUSE_LL emulation never delivers a SendInput-synthesized click to the
    // hook here, unlike real Windows (see Win32ClickWatcher's Javadoc and
    // docs/win32-status.md) - excluded from the pom.xml Wine-forked test run via this
    // tag so that run doesn't permanently fail on it, while windows-ci.yml (real
    // windows-latest, no Wine involved) still exercises it normally.
    @Tag("wine-incompatible")
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
     * A burst of clicks should nearly all still reach the callback. Wine
     * doesn't deliver {@code SendInput} clicks to the hook at all, hence
     * {@code @Tag("wine-incompatible")}.
     */
    @Tag("wine-incompatible")
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
            Thread.sleep(50);
        }

        assertTrue(hits.get() >= (int) (burst * 0.8),
                "only " + hits.get() + "/" + burst + " clicks reached the callback - the "
                        + "WH_MOUSE_LL hook was likely dropped for overrunning LowLevelHooksTimeout");
    }
}
