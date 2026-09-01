package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.Platform;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Simplified 1:1 client-side facade dispatched by {@code os.name} to either
 * an X11 backend ({@link EmbedPlugX11}, over {@link EmbedClient}) or a
 * Win32 backend ({@link EmbedPlugWin32}), matching {@code
 * jembetter-host.EmbedHost} on the other side.
 *
 * <p>Reparenting is host-initiated on both backends (confirmed for Win32 by
 * a real-machine spike — see {@code jembetter-core-win32}'s package-info), so
 * this class's role is the same either way: make this process's own
 * top-level window findable/watchable and wait for the host to act. {@code
 * wmClass} disambiguation (see {@link #announce(String)}) has no Win32
 * equivalent — {@link EmbedPlugWin32} requires it to be {@code null}.
 */
public interface EmbedPlug extends AutoCloseable {

    /** Creates a plug backed by this process's default client implementation for the current OS. */
    static EmbedPlug create() {
        return Platform.isWindows() ? new EmbedPlugWin32() : new EmbedPlugX11();
    }

    /**
     * Does everything needed to become embeddable except dial a host
     * socket, for a host that already knows this process's pid directly —
     * see {@link EmbedClient#announce(String)}. Pass {@code null} for a
     * process with a single top-level window (required on the Win32
     * backend, which has no {@code WM_CLASS} equivalent to disambiguate
     * with).
     */
    void announce(String wmClass);

    /**
     * Hands this process's pid to the host listening at {@code hostSocket}
     * so it can look this process's window up and reparent it — see {@link
     * EmbedClient#offer(Path, String)}. Pass {@code null} for {@code
     * wmClass} for a process with a single top-level window (required on
     * the Win32 backend).
     */
    void announce(Path hostSocket, String wmClass);

    /**
     * Registers a callback invoked once this window has been reparented
     * into an embedder, with the embedder's window id — see {@link
     * EmbedClient#onEmbedded}.
     */
    void onEmbedded(LongConsumer callback);

    /**
     * Registers a callback invoked when the embedding host's process exits
     * or crashes — see {@link EmbedClient#onHostDetached}.
     */
    void onHostDetached(Runnable callback);

    /**
     * Registers a callback invoked when this window gains ({@code true}) or
     * loses ({@code false}) input focus — see {@link
     * EmbedClient#onFocusChanged}.
     *
     * <p><b>X11 backend only.</b> The Win32 backend has no equivalent
     * externally-observable signal (a child HWND's {@code
     * WM_SETFOCUS}/{@code WM_KILLFOCUS} only reach the client's own message
     * loop), so the callback registered here is never invoked on Windows —
     * see {@link EmbedPlugWin32}.
     */
    void onFocusChanged(FocusListener callback);

    /** Stops watching for host death. */
    @Override
    void close();
}
