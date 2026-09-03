package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.ModalityListener;
import cz.loplex.jembetter.common.SizeListener;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32ConfigureWatcher;
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
import java.util.function.LongConsumer;

/**
 * {@link EmbedClient}'s Win32 implementation — the advanced-API counterpart
 * to {@link EmbedClientX11}, matching the {@link EmbedPlug}/{@link
 * EmbedPlugWin32} split on the same side. Resolves and watches this
 * process's own top-level window
 * ({@link #announce}/{@link #offer}, mirroring {@code
 * EmbedPlugWin32#announce} — {@link EmbedPlugWin32} stays usable
 * independently, this class does not delegate to it), exposes {@link
 * #onEmbedded}/{@link #onHostDetached}/{@link #onFocusChanged}/{@link
 * #onResized}/{@link #requestFocus()}, and connects to {@code
 * jembetter-host}'s {@code EmbedSocketWin32#setModal(boolean)} the same way
 * it always has: dial the host's rendezvous socket exactly like {@code
 * EmbedPlugWin32#announce(Path, String)} does (same {@link PidHandshake}),
 * but keep the channel open afterward instead of closing it. A background
 * thread reads {@link ControlMessage} frames off that channel for the life of
 * the embed and dispatches {@link ControlMessage.Type#MODALITY} ones to
 * {@link #onModalityChanged}; {@link #requestFocus()} writes a {@link
 * ControlMessage.Type#FOCUS_REQUEST} frame the other direction on the same
 * channel, read back by {@code EmbedSocketWin32}'s own per-client
 * control-channel reader.
 *
 * <p>Does not (yet) plug into {@code EmbedPlug}'s narrow facade — {@code
 * EmbedPlugWin32#announce(Path, String)} still closes its handshake channel
 * immediately after sending the pid, unaffected by this class's existence.
 */
public final class EmbedClientWin32 implements EmbedClient {

    private static final long POLL_SLEEP_MILLIS = 50;

    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);

    private final Win32ReparentWatcher reparentWatcher = new Win32ReparentWatcher();
    private final Win32FocusWatcher focusWatcher = new Win32FocusWatcher();
    private final Win32ConfigureWatcher configureWatcher = new Win32ConfigureWatcher();
    private long windowId = -1;
    private volatile long embedderHwnd = -1;
    private volatile SocketChannel controlChannel;
    private volatile Thread readerThread;
    private volatile ModalityListener onModalityChanged = modal -> {
    };
    private volatile LongConsumer onEmbedded = embedderId -> {
    };
    private volatile Runnable onHostDetached = () -> {
    };
    private volatile FocusListener onFocusChanged = focused -> {
    };
    private volatile SizeListener onResized = (width, height) -> {
    };

    /**
     * Does everything {@link #announce(String)} does, then dials the host's
     * rendezvous socket exactly like {@link #connect}. For a process with a
     * single top-level window (Win32 has no {@code WM_CLASS} equivalent —
     * see {@link #announce(String)}).
     */
    @Override
    public void offer(Path hostSocketPath) {
        offer(hostSocketPath, null);
    }

    /** Same as {@link #offer(Path)}: {@code wmClass} must be {@code null} — see {@link #announce(String)}. */
    @Override
    public void offer(Path hostSocketPath, String wmClass) {
        announce(wmClass);
        connect(hostSocketPath);
    }

    /** Same as {@link #announce(String)}, for a process with a single top-level window. */
    @Override
    public void announce() {
        announce(null);
    }

    /**
     * Resolves this process's own top-level window and starts watching it
     * for {@link #onEmbedded}/{@link #onHostDetached}/{@link
     * #onFocusChanged}/{@link #onResized} — the Win32 mechanics {@code
     * EmbedPlugWin32#announce(String)} already has, folded into this class
     * instead so a caller wanting the modality channel too doesn't need both
     * classes watching the same window independently. {@code wmClass} must
     * be {@code null}: Win32 has no {@code WM_CLASS} equivalent to
     * disambiguate multiple top-level windows with, so this process must own
     * exactly one.
     */
    @Override
    public void announce(String wmClass) {
        if (wmClass != null) {
            throw new UnsupportedOperationException(
                    "Win32 has no WM_CLASS equivalent to disambiguate by; wmClass must be null "
                            + "(this process must own exactly one top-level window)");
        }
        long pid = ProcessHandle.current().pid();
        windowId = waitForOwnWindow(pid);
        reparentWatcher.watch(windowId, this::handleParentChanged);
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
        configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
    }

    /**
     * Registers a callback invoked once this window has been reparented into
     * an embedder, with the embedder's window handle. Runs on {@link
     * Win32ReparentWatcher}'s own background thread.
     */
    @Override
    public void onEmbedded(LongConsumer callback) {
        onEmbedded = callback;
    }

    /**
     * Registers a callback invoked when this window is released back to the
     * desktop after having been embedded, or when the embedding host's
     * process destroys it outright (Win32 has no save-set to survive that —
     * see {@code Win32ReparentWatcher}'s Javadoc). Runs on {@link
     * Win32ReparentWatcher}'s own background thread.
     */
    @Override
    public void onHostDetached(Runnable callback) {
        onHostDetached = callback;
    }

    /**
     * Registers a callback invoked whenever this window gains or loses Win32
     * input focus — see {@link Win32FocusWatcher}'s Javadoc for the
     * mechanism. Runs on {@link Win32FocusWatcher}'s own background thread.
     */
    @Override
    public void onFocusChanged(FocusListener callback) {
        onFocusChanged = callback;
    }

    /**
     * Registers a callback invoked whenever this window's own size changes —
     * see {@link Win32ConfigureWatcher}'s Javadoc for the mechanism. Runs on
     * {@link Win32ConfigureWatcher}'s own background thread.
     */
    @Override
    public void onResized(SizeListener callback) {
        onResized = callback;
    }

    /** The embedder's window handle last reported to {@link #onEmbedded}, or -1 if not currently embedded. */
    @Override
    public long embedderWindowId() {
        return embedderHwnd;
    }

    /** Parity shim — see {@link EmbedClient#setWindowLookupTimeout}. */
    @Override
    public void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = timeout;
    }

    /**
     * Asks the host to give this window input focus, by writing a {@link
     * ControlMessage.Type#FOCUS_REQUEST} frame to the control channel {@link
     * #connect} opened — read back by {@code EmbedSocketWin32}'s own
     * per-client reader, the client-to-host counterpart of {@link
     * #onModalityChanged}'s host-to-client direction on the same channel.
     * No-op if not currently connected, or best-effort if the host has
     * already closed its end (same "no receiver required" framing as {@code
     * EmbedSocketWin32#setModal}).
     */
    @Override
    public void requestFocus() {
        SocketChannel channel = controlChannel;
        if (channel == null) {
            return;
        }
        try {
            ControlMessage.focusRequest().writeTo(channel);
        } catch (IOException e) {
            // Best-effort, no-receiver-required send - see this method's own Javadoc.
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
        } else {
            embedderHwnd = newParent;
            onEmbedded.accept(newParent);
        }
    }

    private long waitForOwnWindow(long pid) {
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        List<Long> ownWindows;
        do {
            ownWindows = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (ownWindows.size() == 1) {
                return ownWindows.get(0);
            }
            if (ownWindows.size() > 1) {
                throw new IllegalStateException("This process has " + ownWindows.size()
                        + " application windows; Win32 has no WM_CLASS-equivalent way to disambiguate them");
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Could not resolve this process's own top-level window");
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Connects to the host's rendezvous socket at {@code hostSocketPath} and
     * sends this process's own pid — the same handshake {@code
     * EmbedPlugWin32#announce(Path, String)} performs — but keeps the
     * channel open afterward and starts a background thread reading {@link
     * ControlMessage} frames off it, dispatching {@link
     * ControlMessage.Type#MODALITY} ones to {@link #onModalityChanged}. Only
     * meaningful against a host that keeps its own
     * end open too, i.e. one embedding this client via {@code
     * EmbedSocketWin32#listen(Path)} — a plain {@code embed(long)}/{@code
     * embed(Path)}/{@code embedOpaque(long)} host closes its side of the
     * handshake channel right away, so nothing would ever arrive here either
     * way.
     */
    public void connect(Path hostSocketPath) {
        if (controlChannel != null) {
            throw new IllegalStateException("Already connected");
        }
        SocketChannel channel;
        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(address);
            PidHandshake.send(channel, ProcessHandle.current().pid());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        controlChannel = channel;
        readerThread = new Thread(this::readLoop, "jembetter-win32-embed-client-control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Registers a callback invoked each time the host signals this client is
     * shadowed by (or no longer shadowed by) a modal dialog, via {@code
     * EmbedSocketWin32#setModal(boolean)}. Runs on this class's own
     * background reader thread.
     */
    @Override
    public void onModalityChanged(ModalityListener callback) {
        onModalityChanged = callback;
    }

    private void readLoop() {
        SocketChannel channel = controlChannel;
        try {
            ControlMessage message;
            while ((message = ControlMessage.readFrom(channel)) != null) {
                if (message.type() == ControlMessage.Type.MODALITY) {
                    onModalityChanged.modalityChanged(message.flag());
                }
                // No other frame type flows host->client on this backend today.
            }
        } catch (IOException e) {
            // close() closes the channel to unblock this read() as its
            // shutdown signal; the host disappearing does the same via EOF
            // above, not this branch. Either way, nothing left to read.
        }
    }

    @Override
    public void close() {
        SocketChannel channel = controlChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // Best-effort cleanup of a channel already headed nowhere useful.
            }
        }
        Thread thread = readerThread;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        reparentWatcher.close();
        focusWatcher.close();
        configureWatcher.close();
    }
}
