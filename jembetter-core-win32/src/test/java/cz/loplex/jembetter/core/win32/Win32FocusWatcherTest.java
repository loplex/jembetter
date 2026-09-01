package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createVisibleTopLevelWindowAt;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32FocusWatcher} against real HWNDs and a real {@code
 * SetFocus} call (via {@link Win32Focus#set}, not synthesized input, unlike
 * {@link Win32ClickWatcherTest}) — that the {@code SetWinEventHook} hook
 * installs, its {@code WinEventProc} callback marshals, the message pump
 * dispatches, and {@code close()} unhooks cleanly. {@code @Tag("windows")}
 * like this package's other primitive tests, so it runs on real Windows in
 * {@code windows-ci.yml} and under Wine via {@code mvn test}'s {@code
 * windows-tests-on-linux} execution.
 */
@Tag("windows")
class Win32FocusWatcherTest {

    private Win32FocusWatcher watcher;
    private long hwndA;
    private long hwndB;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        destroyWindow(hwndA);
        destroyWindow(hwndB);
    }

    // Wine's SetWinEventHook emulation never delivers a real EVENT_OBJECT_FOCUS
    // to the hook here, unlike real Windows (see Win32FocusWatcher's Javadoc
    // and docs/win32-status.md) - excluded from the pom.xml Wine-forked test
    // run via this tag, the same way Win32ClickWatcherTest's WH_MOUSE_LL
    // event-delivery cases are, so that run doesn't permanently fail on it,
    // while windows-ci.yml (real windows-latest, no Wine involved) still
    // exercises it normally.
    @Tag("wine-incompatible")
    @Test
    void settingFocusOnAWatchedWindowInvokesTheCallbackWithTrue() throws InterruptedException {
        hwndA = createVisibleTopLevelWindowAt("Win32FocusWatcherTest A", 120, 120, 200, 150);
        watcher = new Win32FocusWatcher();

        CountDownLatch gained = new CountDownLatch(1);
        AtomicBoolean sawFocused = new AtomicBoolean(false);
        watcher.watch(hwndA, focused -> {
            sawFocused.set(focused);
            gained.countDown();
        });

        Win32Focus.set(hwndA);

        assertTrue(gained.await(3, TimeUnit.SECONDS), "SetFocus on the watched window never reached the callback");
        assertTrue(sawFocused.get(), "the callback was invoked with focused=false for a window that just gained focus");
    }

    @Tag("wine-incompatible")
    @Test
    void movingFocusAwayInvokesTheCallbackWithFalse() throws InterruptedException {
        hwndA = createVisibleTopLevelWindowAt("Win32FocusWatcherTest away-A", 120, 120, 200, 150);
        hwndB = createVisibleTopLevelWindowAt("Win32FocusWatcherTest away-B", 340, 120, 200, 150);
        watcher = new Win32FocusWatcher();

        CountDownLatch gained = new CountDownLatch(1);
        watcher.watch(hwndA, focused -> {
            if (focused) {
                gained.countDown();
            }
        });
        Win32Focus.set(hwndA);
        assertTrue(gained.await(3, TimeUnit.SECONDS), "setup: SetFocus on hwndA never reached the callback");

        CountDownLatch lost = new CountDownLatch(1);
        watcher.watch(hwndA, focused -> {
            if (!focused) {
                lost.countDown();
            }
        });
        Win32Focus.set(hwndB);

        assertTrue(lost.await(3, TimeUnit.SECONDS), "moving focus away from the watched window never reached the callback");
    }

    @Test
    void unwatchStopsFurtherCallbacks() throws InterruptedException {
        hwndA = createVisibleTopLevelWindowAt("Win32FocusWatcherTest unwatch", 120, 120, 200, 150);
        watcher = new Win32FocusWatcher();

        CountDownLatch fired = new CountDownLatch(1);
        watcher.watch(hwndA, focused -> fired.countDown());
        watcher.unwatch(hwndA);

        Win32Focus.set(hwndA);

        assertFalse(fired.await(1, TimeUnit.SECONDS), "an unwatched window still invoked the callback");
    }

    @Test
    void closeUnhooksWithoutThrowing() {
        assertDoesNotThrow(() -> new Win32FocusWatcher().close());
    }
}
