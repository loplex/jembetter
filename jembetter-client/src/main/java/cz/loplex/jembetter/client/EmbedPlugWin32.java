package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32FocusWatcher;
import cz.loplex.jembetter.core.win32.Win32ReparentWatcher;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * {@link EmbedPlug}'s Win32 implementation. Reparenting is host-initiated on
 * this backend (see {@code jembetter-host.EmbedHostWin32}'s Javadoc) — there is
 * no {@code _XEMBED_INFO}-equivalent handshake for this class to publish, so
 * {@link #announce} just resolves this process's own top-level HWND and
 * starts watching it; the host does {@code SetParent} on its own initiative
 * once it has learned (or already knew) this process's pid.
 *
 * <p>{@code wmClass} has no Win32 equivalent (no {@code WM_CLASS} property
 * exists to match against) — {@link #announce(String)}/{@link
 * #announce(Path, String)} require it to be {@code null} on this backend,
 * meaning the client process must own exactly one top-level window.
 *
 * <p><b>Detecting the embed and the host detaching is a poll-based
 * implementation choice, not something the real-machine spike verified</b> —
 * see {@link Win32ReparentWatcher}'s Javadoc. One asymmetry worth calling
 * out versus the X11 side: X11's save-set mechanism reparents a released
 * window back to the root window, alive and visible, as soon as the
 * embedder's connection closes; Win32 has no such mechanism; destroying a
 * parent HWND destroys its children outright. So by the time {@link
 * #onHostDetached} fires here, this process's own previously-embedded window
 * may itself already be gone, not merely un-parented — a caller can't
 * necessarily reuse it the way it could on X11.
 */
final class EmbedPlugWin32 implements EmbedPlug {

    private static final Duration WINDOW_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_SLEEP_MILLIS = 50;

    private final Win32ReparentWatcher watcher = new Win32ReparentWatcher();
    private final Win32FocusWatcher focusWatcher = new Win32FocusWatcher();
    private long windowId = -1;
    private volatile long embedderHwnd = -1;
    private volatile boolean awaitingEmbed = false;
    private volatile Runnable onHostDetached = () -> {
    };
    private volatile LongConsumer onEmbedded = embedderId -> {
    };
    private volatile FocusListener onFocusChanged = focused -> {
    };

    @Override
    public void announce(String wmClass) {
        requireNoWmClass(wmClass);
        long pid = ProcessHandle.current().pid();
        windowId = waitForOwnWindow(pid);
        awaitingEmbed = true;
        watcher.watch(windowId, this::handleParentChanged, this::handleWindowDestroyed);
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
    }

    @Override
    public void announce(Path hostSocket, String wmClass) {
        Objects.requireNonNull(hostSocket, "hostSocket");
        announce(wmClass);
        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocket);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, ProcessHandle.current().pid());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void onEmbedded(LongConsumer callback) {
        onEmbedded = Objects.requireNonNull(callback, "callback");
    }

    @Override
    public void onHostDetached(Runnable callback) {
        onHostDetached = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Registers a callback invoked when this window gains or loses Win32
     * input focus, via {@link Win32FocusWatcher} — see that class's own
     * Javadoc for the mechanism. An embedded child HWND's own {@code
     * WM_SETFOCUS}/{@code WM_KILLFOCUS} are delivered only inside this
     * process's own message loop and can't be observed cross-process
     * directly, unlike X11's real, server-generated {@code FocusIn}/{@code
     * FocusOut} events {@code WindowFocusWatcher} reads; {@link
     * Win32FocusWatcher} instead watches system-wide via {@code
     * SetWinEventHook(EVENT_OBJECT_FOCUS, ...)}, which does observe a focus
     * change caused by another process (e.g. the host calling {@code
     * SetFocus} on this window).
     */
    @Override
    public void onFocusChanged(FocusListener callback) {
        onFocusChanged = Objects.requireNonNull(callback, "callback");
    }


    @Override
    public boolean closedCleanly() {
        return watcher.stoppedCleanly() && focusWatcher.stoppedCleanly();
    }

    @Override
    public void close() {
        watcher.close();
        focusWatcher.close();
    }

    /**
     * The watched window no longer exists. On this backend that is how a host
     * dying is normally seen — destroying a parent HWND destroys its children
     * outright, with no X11-style save-set to leave the client's window alive
     * — so it is reported as the host detaching, which is what {@code
     * onHostDetached} documents itself to cover.
     *
     * <p>Handled separately from {@link #handleParentChanged} because the
     * parent-based path cannot see this case at all when the embed and the
     * destruction fall inside one poll interval: the parent reads 0 before and
     * after, so nothing appears to change and no embed is ever recorded. Both
     * paths clear the state they check, so whichever runs first is the only
     * one that reports.
     */
    private void handleWindowDestroyed() {
        if (embedderHwnd >= 0) {
            embedderHwnd = -1;
            awaitingEmbed = false;
            onHostDetached.run();
        } else if (awaitingEmbed) {
            awaitingEmbed = false;
            onHostDetached.run();
        }
    }

    private void handleParentChanged(long newParent) {
        if (newParent == 0) {
            if (embedderHwnd >= 0) {
                embedderHwnd = -1;
                onHostDetached.run();
            }
            // else: not embedded yet - this window's own parent is 0 until a
            // host calls SetParent on it, nothing to report.
        } else if (awaitingEmbed) {
            awaitingEmbed = false;
            embedderHwnd = newParent;
            onEmbedded.accept(newParent);
        }
        // else: some other non-zero parent while not expecting an embed - a
        // desktop shell taking this window into a frame of its own, say - not
        // an embed, ignore. Same filter EmbedClientWin32 and EmbedClientX11
        // apply; without it any reparent at all reads as a host embedding us.
    }

    private static void requireNoWmClass(String wmClass) {
        if (wmClass != null) {
            throw new UnsupportedOperationException(
                    "Win32 has no WM_CLASS equivalent to disambiguate by; wmClass must be null "
                            + "(this process must own exactly one top-level window)");
        }
    }

    private long waitForOwnWindow(long pid) {
        long deadline = System.nanoTime() + WINDOW_LOOKUP_TIMEOUT.toNanos();
        List<Long> ownWindows;
        do {
            ownWindows = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (ownWindows.size() == 1) {
                return ownWindows.getFirst();
            }
            if (ownWindows.size() > 1) {
                throw new IllegalStateException("This process has " + ownWindows.size()
                        + " application windows; Win32 has no WM_CLASS-equivalent way to disambiguate them");
            }
            sleep(POLL_SLEEP_MILLIS);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Could not resolve this process's own top-level window");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
