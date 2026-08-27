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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fires a callback when a real left click lands inside a watched window's
 * screen rect — the Win32 stand-in for {@code EmbedSocket}'s X11
 * click-to-focus ({@code jembetter-core}, a passive {@code XGrabButton} that
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
 * <p><b>Poll-free but still an implementation choice not verified on a real
 * Windows machine</b> — see this module's package-info. A {@code
 * .mvn/win32-wine-smoketest} run confirms the hook installs, the {@code
 * LowLevelMouseProc}/{@code MSLLHOOKSTRUCT} marshaling works, the message
 * pump dispatches, the hit-test is correct, and {@code close()} unhooks
 * cleanly. What that cannot confirm — and what a real-machine spike still
 * owes — is the documented Win32 caveats: the added system-wide mouse
 * latency while the hook is installed, and UIPI blocking the hook against a
 * higher-integrity-level target. Mirrors {@link Win32Focus}'s {@code
 * AttachThreadInput} fallback and {@link Win32ReparentWatcher} in that
 * respect.
 */
public final class Win32ClickWatcher implements AutoCloseable {

    private static final int WH_MOUSE_LL = 14;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_QUIT = 0x0012;
    private static final int HC_ACTION = 0;

    private final Map<Long, Runnable> callbacks = new ConcurrentHashMap<>();
    private final ExecutorService dispatch =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jembetter-win32-click-dispatch");
                thread.setDaemon(true);
                return thread;
            });
    private final Thread pumpThread;
    private final CountDownLatch installed = new CountDownLatch(1);

    // Strong reference: JNA collects an unreferenced callback, which crashes
    // the process the next time Windows invokes the hook.
    private final LowLevelMouseProc hookProc = this::onMouseEvent;

    private volatile HHOOK hook;
    private volatile int pumpThreadId;
    private volatile boolean running = true;

    public Win32ClickWatcher() {
        this.pumpThread = new Thread(this::pump, "jembetter-win32-click-watcher");
        pumpThread.setDaemon(true);
        pumpThread.start();
        // The watcher isn't functional until the hook is actually installed
        // on the pump thread; block here so a click right after construction
        // can't race past a not-yet-installed hook.
        awaitInstalled();
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
        installed.countDown();
        if (hook == null) {
            return;
        }
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

    private LRESULT onMouseEvent(int nCode, WPARAM wParam, MSLLHOOKSTRUCT info) {
        if (nCode >= HC_ACTION && wParam.intValue() == WM_LBUTTONDOWN && info != null) {
            int x = info.pt.x;
            int y = info.pt.y;
            for (Map.Entry<Long, Runnable> entry : callbacks.entrySet()) {
                if (contains(entry.getKey(), x, y)) {
                    Runnable callback = entry.getValue();
                    dispatch.execute(() -> runQuietly(callback));
                }
            }
        }
        LPARAM lParam = new LPARAM(Pointer.nativeValue(info.getPointer()));
        return User32.INSTANCE.CallNextHookEx(hook, nCode, wParam, lParam);
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
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        running = false;
        awaitInstalled();
        int threadId = pumpThreadId;
        if (threadId != 0) {
            User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT, new WPARAM(0), new LPARAM(0));
        }
        try {
            pumpThread.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dispatch.shutdownNow();
    }

    private void awaitInstalled() {
        try {
            installed.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
