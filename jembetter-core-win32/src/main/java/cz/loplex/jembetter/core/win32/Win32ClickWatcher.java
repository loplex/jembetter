package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.HHOOK;
import com.sun.jna.platform.win32.WinUser.LowLevelMouseProc;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.MSLLHOOKSTRUCT;

import cz.loplex.jembetter.common.BackgroundThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fires a callback when a real left click lands inside a watched window's
 * screen rect — the Win32 stand-in for {@code EmbedSocket}'s X11
 * click-to-focus ({@code jembetter-core-x11}, a passive {@code XGrabButton} that
 * intercepts the press and replays it). No X11-style intercept-and-replay is
 * possible here: this is observe-and-react. A single system-wide low-level
 * mouse hook ({@code SetWindowsHookEx(WH_MOUSE_LL, ...)}, which runs in this
 * process — no DLL injected into the clicked window's process) watches every
 * {@code WM_LBUTTONDOWN}; each is hit-tested (by screen coordinate, via
 * {@code GetWindowRect}) against every watched HWND, and matching callbacks
 * run on a private dispatch thread so the hook procedure itself returns
 * within Windows' {@code LowLevelHooksTimeout}. The hook never blocks or
 * alters the click — {@code CallNextHookEx} is always called.
 *
 * <p>Injected clicks (those carrying {@code LLMHF_INJECTED}, e.g. from {@code
 * SendInput}) are <em>not</em> filtered out: a synthesized click into the
 * embedded area asking for focus is as legitimate as a hardware one, and it
 * keeps the mechanism exercisable without real hardware input.
 *
 * <p><b>Poll-free.</b> This class's {@code @Tag("windows")} tests, run under
 * Wine by {@code mvn test} on Linux (the {@code windows-tests-on-linux}
 * surefire execution), confirm the hook installs, the {@code
 * LowLevelMouseProc}/{@code MSLLHOOKSTRUCT} marshaling works, the message
 * pump dispatches, the hit-test is correct, and {@code close()} unhooks
 * cleanly. The 2026-08-28 real-machine spike (see this module's
 * package-info) additionally confirmed that under a burst of injected clicks
 * the dispatch-thread offload keeps the hook proc under {@code
 * LowLevelHooksTimeout} (every click still reached the callback), and
 * measured the added system-wide mouse latency while installed at a few
 * microseconds per event — negligible on that runner. Still <b>not</b>
 * spiked: UIPI blocking the hook against a higher-integrity-level target
 * (the CI runner process is itself elevated, so the blocking direction
 * can't be exercised there).
 */
public final class Win32ClickWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Win32ClickWatcher.class);

    private volatile boolean stoppedCleanly = true;

    private static final int WH_MOUSE_LL = 14;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_QUIT = 0x0012;
    private static final int HC_ACTION = 0;
    /**
     * How long {@link #Win32ClickWatcher()} waits for the pump thread to
     * report its hook. Matched to the 5s the rest of the library allows for
     * "the window system should answer promptly but might not": under Wine,
     * {@code SetWindowsHookEx} has been measured taking over a second to
     * return while several test forks are running. The wait exists to close a
     * startup race, not to time the call, so the budget has to cover the
     * slowest environment this runs in — a value that is too small does not
     * report a problem, it manufactures one.
     *
     * <p>It has been too small at least once: 5s failed 2 of 4 full runs of
     * {@code jembetter-host} on a machine ninety minutes into running test
     * suites. Use {@link #Win32ClickWatcher(Duration)} where that is the
     * normal condition rather than raising this for everyone.
     */
    public static final Duration DEFAULT_INSTALL_TIMEOUT = Duration.ofSeconds(5);

    private final Map<Long, Runnable> callbacks = new ConcurrentHashMap<>();
    private final ExecutorService dispatch =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jembetter-win32-click-dispatch");
                thread.setDaemon(true);
                return thread;
            });
    private final Thread pumpThread;
    private final CountDownLatch installed = new CountDownLatch(1);
    /** Kept so close() waits on the same budget the constructor did, not a second guess at one. */
    private final Duration installTimeout;

    // Strong reference: JNA collects an unreferenced callback, which crashes
    // the process the next time Windows invokes the hook.
    private final LowLevelMouseProc hookProc = this::onMouseEvent;

    private volatile HHOOK hook;
    private volatile int installErrorCode;
    private volatile int pumpThreadId;
    private volatile boolean running = true;

    /** A watcher with the {@linkplain #DEFAULT_INSTALL_TIMEOUT default install budget}. */
    public Win32ClickWatcher() {
        this(DEFAULT_INSTALL_TIMEOUT);
    }

    /**
     * A watcher that waits up to {@code installTimeout} for its hook.
     *
     * <p>Worth setting when this runs somewhere the default does not cover —
     * a loaded CI machine, or several Wine forks at once, where {@code
     * SetWindowsHookEx} has been measured taking over a second to return.
     * Measured 2026-09-15 on a machine that had been running test suites for
     * ninety minutes: 2 of 4 full runs of {@code jembetter-host} failed
     * construction at the 5 s default, against 0 of 40 full-suite iterations
     * on CI.
     *
     * @throws IllegalStateException if the hook could not be installed, or
     *         was not installed inside the budget — the two are distinct, and
     *         the message says which
     */
    public Win32ClickWatcher(Duration installTimeout) {
        Objects.requireNonNull(installTimeout, "installTimeout");
        this.installTimeout = installTimeout;
        this.pumpThread = new Thread(this::pump, "jembetter-win32-click-watcher");
        pumpThread.setDaemon(true);
        pumpThread.start();
        // The watcher isn't functional until the hook is actually installed
        // on the pump thread; block here so a click right after construction
        // can't race past a not-yet-installed hook.
        boolean reported = awaitInstalled(installTimeout);
        if (reported && hook != null) {
            return;
        }
        // Fail, rather than hand back a watcher that will never report a
        // click. Everything this class does depends on that one hook, so an
        // instance without it is not a degraded watcher, it is a silent no-op
        // - and the caller has no way to ask whether it worked. close()
        // first, because the caller gets no object and so can never stop the
        // pump thread itself.
        close();
        if (reported) {
            // The pump thread got an answer and it was "no". A real failure:
            // retrying or waiting longer changes nothing.
            throw new IllegalStateException(
                    "SetWindowsHookEx(WH_MOUSE_LL) failed with error " + installErrorCode);
        }
        // The pump thread has not answered yet - which is not the same thing,
        // and saying so matters, because the two want opposite responses. The
        // call may well be about to succeed; what ran out is the budget. The
        // message names it as a budget and says how to raise it, so a slow
        // machine does not read as a broken one.
        throw new IllegalStateException(
                "The click watcher's pump thread did not report its hook within " + installTimeout
                        + ". SetWindowsHookEx has not failed - it has not answered yet, and this budget "
                        + "ran out first. On a loaded machine that is the expected outcome rather than a "
                        + "defect; construct with a longer Duration if this is one.");
    }

    /**
     * Starts firing {@code onClickInside} (on a private dispatch thread)
     * whenever a left click lands inside {@code hwnd}'s current screen rect,
     * until {@link #unwatch} or {@link #close}.
     */
    public void watch(long hwnd, Runnable onClickInside) {
        callbacks.put(hwnd, onClickInside);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
    }

    private void pump() {
        pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        hook = User32.INSTANCE.SetWindowsHookEx(WH_MOUSE_LL, hookProc, null, 0);
        if (hook == null) {
            // Read before countDown: the constructor reports this, and
            // GetLastError is per-thread, so it has to be captured here.
            installErrorCode = Kernel32.INSTANCE.GetLastError();
            installed.countDown();
            return;
        }
        installed.countDown();
        try {
            MSG msg = new MSG();
            int result;
            while (running && (result = User32.INSTANCE.GetMessage(msg, null, 0, 0)) != 0) {
                if (result == -1) {
                    break;
                }
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }
        } finally {
            User32.INSTANCE.UnhookWindowsHookEx(hook);
            hook = null;
        }
    }

    /**
     * Runs on the pump thread, for every low-level mouse event the system
     * generates, and does as little as it possibly can.
     *
     * <p>Windows silently removes a low-level hook whose callback overruns
     * {@code LowLevelHooksTimeout} — no notification, no error, and no API
     * to ask afterwards whether the hook is still installed. A watcher whose
     * hook was removed goes permanently, silently dead, which is the same
     * failure the constructor now refuses to hand back at install time and
     * the one thing that cannot be detected once running. So the only real
     * defence is to never come close to the limit: this reads two ints and
     * hands them off. No Win32 call, no per-watched-window work, nothing
     * that scales with anything.
     */
    private LRESULT onMouseEvent(int nCode, WPARAM wParam, MSLLHOOKSTRUCT info) {
        if (nCode >= HC_ACTION && wParam.intValue() == WM_LBUTTONDOWN && info != null) {
            int x = info.pt.x;
            int y = info.pt.y;
            dispatch.execute(() -> notifyWatchersAt(x, y));
        }
        // info is checked for null here too, not only above: an exception
        // thrown out of a JNA callback has nowhere to go, and a null here
        // would have been a NullPointerException on the hook thread.
        LPARAM lParam = info == null ? new LPARAM(0) : new LPARAM(Pointer.nativeValue(info.getPointer()));
        return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
    }

    /**
     * The half of a click that needs Win32 calls — which window was under
     * the pointer — moved off the hook thread onto the dispatch thread. A
     * rect read a moment after the click rather than during it is the same
     * answer for any window that is not being dragged at that instant.
     */
    private void notifyWatchersAt(int x, int y) {
        for (Map.Entry<Long, Runnable> entry : callbacks.entrySet()) {
            try {
                if (contains(entry.getKey(), x, y)) {
                    runQuietly(entry.getValue());
                }
            } catch (RuntimeException e) {
                // One unreadable window must not hide a click from the rest.
                LOG.warn("Testing whether a click landed inside window {} failed", entry.getKey(), e);
            }
        }
    }

    private static boolean contains(long hwnd, int screenX, int screenY) {
        HWND handle = new HWND(new Pointer(hwnd));
        if (!User32.INSTANCE.IsWindow(handle)) {
            return false;
        }
        RECT rect = new RECT();
        if (!User32.INSTANCE.GetWindowRect(handle, rect)) {
            return false;
        }
        return screenX >= rect.left && screenX < rect.right
                && screenY >= rect.top && screenY < rect.bottom;
    }

    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            // A misbehaving callback must not take the dispatch thread down.
            LOG.warn("A click callback threw", e);
        }
    }


    /**
     * Whether {@link #close()} actually stopped this watcher's thread, or
     * gave up on it after {@link BackgroundThread#STOP_BUDGET}. {@code true}
     * until a close that fails to.
     */
    public boolean stoppedCleanly() {
        return stoppedCleanly;
    }

    @Override
    public void close() {
        running = false;
        // The pump thread publishes its id before it reports, so this is what
        // makes PostThreadMessage below reach a thread that exists. Same budget
        // the constructor used: a close is not the place to pick a different one.
        awaitInstalled(installTimeout);
        int threadId = pumpThreadId;
        if (threadId != 0) {
            User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT, new WPARAM(0), new LPARAM(0));
        }
        stoppedCleanly = BackgroundThread.awaitStopped(pumpThread, LOG);
        dispatch.shutdownNow();
    }

    /**
     * Waits for the pump thread to report whether its hook went in. Returns
     * {@code false} if it never got that far, which the constructor treats
     * as a failed install; {@link #close()} ignores the result and only
     * needs the wait itself, so that {@code pumpThreadId} is published
     * before it posts to it.
     */
    /** Whether the pump thread reported an outcome - either one - inside {@code budget}. */
    private boolean awaitInstalled(Duration budget) {
        try {
            return installed.await(budget.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
