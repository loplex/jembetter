package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WinEventProc;
import cz.loplex.jembetter.common.FocusListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fires a callback when a watched window gains or loses Win32 input focus —
 * the Win32 stand-in for {@code jembetter-core-x11}'s {@code
 * WindowFocusWatcher}, watched by a client process on its own top-level
 * window (see {@code jembetter-client.EmbedPlugWin32#onFocusChanged}).
 *
 * <p>An embedded child HWND's own {@code WM_SETFOCUS}/{@code WM_KILLFOCUS}
 * are delivered only inside that window's own message loop — invisible from
 * here without a hook, and per-window messages can't cross process
 * boundaries the way a genuinely embedded child's messages could if this
 * were the same process. This class instead installs a single system-wide
 * {@code SetWinEventHook(EVENT_OBJECT_FOCUS, ...)}, mirroring {@link
 * Win32ClickWatcher}'s {@code WH_MOUSE_LL} shape: a {@code WINEVENT_OUTOFCONTEXT}
 * hook (no DLL injected anywhere) runs in <em>this</em> process and receives
 * an event for every window in the system gaining focus, regardless of
 * which process or thread actually caused it — including a host process
 * calling {@code SetFocus} on this process's own HWND via {@link
 * Win32Focus#set}'s {@code AttachThreadInput} path. Each event is
 * hit-tested against every watched HWND: the one matching the event's
 * {@code hwnd} just gained focus, and any other watched HWND last reported
 * as focused just lost it — {@code EVENT_OBJECT_FOCUS} only ever signals a
 * gain, never a loss directly.
 *
 * <p>Only genuine transitions are reported, the same deduplication {@code
 * WindowFocusWatcher} does: a watched window never before seen as focused
 * doesn't get a spurious initial "lost" callback, and a repeat event for a
 * window already in its current state is suppressed.
 */
public final class Win32FocusWatcher implements AutoCloseable {

    // Not exposed by JNA's WinUser; values per winuser.h.
    private static final int EVENT_OBJECT_FOCUS = 0x8005;
    private static final int WINEVENT_OUTOFCONTEXT = 0x0000;
    private static final int OBJID_WINDOW = 0x00000000;
    private static final int CHILDID_SELF = 0;
    private static final int WM_QUIT = 0x0012;

    private final Map<Long, FocusListener> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lastReported = new ConcurrentHashMap<>();
    private final ExecutorService dispatch =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jembetter-win32-focus-dispatch");
                thread.setDaemon(true);
                return thread;
            });
    private final Thread pumpThread;
    private final CountDownLatch installed = new CountDownLatch(1);

    // Strong reference: JNA collects an unreferenced callback, which crashes
    // the process the next time Windows invokes the hook - same precaution
    // Win32ClickWatcher takes for its LowLevelMouseProc.
    private final WinEventProc hookProc = this::onFocusEvent;

    private volatile HANDLE hook;
    private volatile int pumpThreadId;
    private volatile boolean running = true;

    public Win32FocusWatcher() {
        this.pumpThread = new Thread(this::pump, "jembetter-win32-focus-watcher");
        pumpThread.setDaemon(true);
        pumpThread.start();
        // The watcher isn't functional until the hook is actually installed
        // on the pump thread; block here so a watch() right after
        // construction can't race past a not-yet-installed hook.
        awaitInstalled();
    }

    /** Starts firing {@code onFocusChanged} (on a private dispatch thread) whenever {@code hwnd}'s focus state changes, until {@link #unwatch} or {@link #close}. */
    public void watch(long hwnd, FocusListener onFocusChanged) {
        callbacks.put(hwnd, onFocusChanged);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
        lastReported.remove(hwnd);
    }

    private void pump() {
        pumpThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
        hook = User32.INSTANCE.SetWinEventHook(EVENT_OBJECT_FOCUS, EVENT_OBJECT_FOCUS, null, hookProc, 0, 0,
                WINEVENT_OUTOFCONTEXT);
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
            User32.INSTANCE.UnhookWinEvent(hook);
            hook = null;
        }
    }

    private void onFocusEvent(HANDLE hWinEventHook, DWORD event, HWND hwnd, LONG idObject, LONG idChild,
            DWORD idEventThread, DWORD dwmsEventTime) {
        if (hwnd == null || idObject == null || idChild == null
                || idObject.intValue() != OBJID_WINDOW || idChild.intValue() != CHILDID_SELF) {
            return;
        }
        long focusedHwnd = Pointer.nativeValue(hwnd.getPointer());
        for (long watched : callbacks.keySet()) {
            boolean isNowFocused = watched == focusedHwnd;
            boolean wasFocused = Boolean.TRUE.equals(lastReported.get(watched));
            if (isNowFocused && !wasFocused) {
                report(watched, true);
            } else if (!isNowFocused && wasFocused) {
                report(watched, false);
            }
        }
    }

    private void report(long hwnd, boolean focused) {
        lastReported.put(hwnd, focused);
        FocusListener callback = callbacks.get(hwnd);
        if (callback == null) {
            return;
        }
        dispatch.execute(() -> runQuietly(callback, focused));
    }

    private static void runQuietly(FocusListener callback, boolean focused) {
        try {
            callback.focusChanged(focused);
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
