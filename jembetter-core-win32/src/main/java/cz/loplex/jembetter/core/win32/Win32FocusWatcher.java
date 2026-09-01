package cz.loplex.jembetter.core.win32;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.GUITHREADINFO;
import com.sun.jna.ptr.IntByReference;
import cz.loplex.jembetter.common.FocusListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires a callback when a watched window gains or loses Win32 input focus —
 * the Win32 stand-in for {@code jembetter-core-x11}'s {@code
 * WindowFocusWatcher}, watched by a client process on its own top-level
 * window (see {@code jembetter-client.EmbedPlugWin32#onFocusChanged}).
 *
 * <p><b>Poll-based, the same way {@link Win32ReparentWatcher} is, and for
 * the same underlying reason:</b> Win32 has no externally-observable event
 * this actually fires on. An embedded child HWND's own {@code
 * WM_SETFOCUS}/{@code WM_KILLFOCUS} are delivered only inside that window's
 * own message loop — invisible from here without a hook. A first attempt
 * used a system-wide {@code SetWinEventHook(EVENT_OBJECT_FOCUS, ...)} hook
 * instead (mirroring {@link Win32ClickWatcher}'s {@code WH_MOUSE_LL} shape),
 * on the assumption that {@code SetFocus} generates that WinEvent for any
 * window the way it always sends {@code WM_SETFOCUS}/{@code WM_KILLFOCUS} —
 * a 2026-09-02 real-machine check ({@code FocusWatcherCheck}, in {@code
 * build-tools/win32-real-machine-checks}) disproved that: {@code
 * GetGUIThreadInfo} confirmed the target window genuinely held focus after
 * {@code Win32Focus#set}, yet the hook's callback never fired, on real
 * {@code windows-latest}. {@code EVENT_OBJECT_FOCUS} is a Microsoft Active
 * Accessibility notification that a window's own message handling has to
 * raise itself via {@code NotifyWinEvent} — standard common controls (edit,
 * button, ...) do that internally on {@code WM_SETFOCUS}, but a plain
 * top-level window (this class's target, whether a bare {@code STATIC}
 * HWND or a real AWT/Swing peer) never does, so the hook had nothing to
 * ever receive.
 *
 * <p>This class instead polls {@code GetGUIThreadInfo} on each watched
 * window's owning thread — the same call the real-machine check used to
 * tell the two failure modes apart, and confirmed reliable cross-thread and
 * cross-process without needing an {@code AttachThreadInput} bridge (unlike
 * plain {@code GetFocus()}, which only ever answers for the calling
 * thread's own queue). Only genuine transitions are reported, the same
 * deduplication {@code WindowFocusWatcher} does: a watched window never
 * before seen as focused doesn't get a spurious initial "lost" callback,
 * repeat polls of an unchanged state fire nothing, and callbacks run
 * directly on the watcher's own poll thread (no separate dispatch thread
 * needed — unlike the abandoned hook-based version, nothing here is a
 * Windows callback under an OS-imposed time budget).
 */
public final class Win32FocusWatcher implements AutoCloseable {

    private static final long POLL_INTERVAL_MILLIS = 50;

    private final Thread thread;
    private final Map<Long, FocusListener> callbacks = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> lastReported = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public Win32FocusWatcher() {
        this.thread = new Thread(this::loop, "jembetter-win32-focus-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    /** Starts firing {@code onFocusChanged} (on the watcher's own poll thread) whenever {@code hwnd}'s focus state changes, until {@link #unwatch} or {@link #close}. */
    public void watch(long hwnd, FocusListener onFocusChanged) {
        callbacks.put(hwnd, onFocusChanged);
    }

    public void unwatch(long hwnd) {
        callbacks.remove(hwnd);
        lastReported.remove(hwnd);
    }

    private void loop() {
        while (running) {
            for (Map.Entry<Long, FocusListener> entry : callbacks.entrySet()) {
                pollOne(entry.getKey(), entry.getValue());
            }
            idle();
        }
    }

    private void pollOne(long hwnd, FocusListener callback) {
        boolean isNowFocused = currentlyFocused(hwnd);
        Boolean previous = lastReported.put(hwnd, isNowFocused);
        boolean wasFocused = Boolean.TRUE.equals(previous);
        if (isNowFocused != wasFocused) {
            runQuietly(callback, isNowFocused);
        }
    }

    private static boolean currentlyFocused(long hwnd) {
        HWND handle = new HWND(new Pointer(hwnd));
        if (!User32.INSTANCE.IsWindow(handle)) {
            return false;
        }
        IntByReference pid = new IntByReference();
        int threadId = User32.INSTANCE.GetWindowThreadProcessId(handle, pid);
        if (threadId == 0) {
            return false;
        }
        GUITHREADINFO info = new GUITHREADINFO();
        info.cbSize = info.size();
        if (!User32.INSTANCE.GetGUIThreadInfo(threadId, info)) {
            return false;
        }
        return info.hwndFocus != null && Pointer.nativeValue(info.hwndFocus.getPointer()) == hwnd;
    }

    private static void runQuietly(FocusListener callback, boolean focused) {
        try {
            callback.focusChanged(focused);
        } catch (RuntimeException e) {
            // A misbehaving callback must not take the watcher thread down.
            e.printStackTrace();
        }
    }

    private void idle() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
