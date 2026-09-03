package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.ModalityListener;
import cz.loplex.jembetter.common.Platform;
import cz.loplex.jembetter.common.SizeListener;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * The advanced, client-side API — the backend-portable type a caller
 * programs against for anything {@link EmbedPlug}'s narrow 1:1 facade leaves
 * out, chiefly {@link #requestFocus()} and {@link #onModalityChanged}.
 * Dispatched by {@code os.name} to an X11 backend ({@link EmbedClientX11})
 * or a Win32 backend ({@link EmbedClientWin32}), matching the {@link
 * EmbedPlug}/{@code EmbedPlugX11}/{@code EmbedPlugWin32} split on the same
 * side.
 *
 * <p>This interface is the <strong>intersection</strong> of what both
 * backends implement (plus a {@link #setWindowLookupTimeout} parity shim).
 * X11-only capabilities — {@link EmbedClientX11#onActivationChanged}
 * (host-window activation; Win32's host has no sender to pair with) and
 * {@link EmbedClientX11#watchOwnWindow} (toolkit-opaque handoff) — live on
 * {@link EmbedClientX11} only; a caller that needs one downcasts to it
 * explicitly.
 */
public interface EmbedClient extends AutoCloseable {

    /** Creates a client backed by this process's default implementation for the current OS. */
    static EmbedClient create() {
        return Platform.isWindows() ? new EmbedClientWin32() : new EmbedClientX11();
    }

    /**
     * Does everything {@link #offer} does except dial a host socket, for a
     * host that already knows this process's pid directly — see {@link
     * EmbedClientX11#announce()}.
     */
    void announce();

    /**
     * Same as {@link #announce()}, but {@code wmClass} disambiguates which
     * top-level window gets announced — see {@link
     * EmbedClientX11#announce(String)}. The Win32 backend has no {@code
     * WM_CLASS} equivalent and throws {@link UnsupportedOperationException}
     * for a non-null {@code wmClass}, matching {@link
     * EmbedPlug#announce(String)}.
     */
    void announce(String wmClass);

    /**
     * Hands this process's pid to the host at {@code hostSocketPath} so it
     * can look this process's window up and reparent it — see {@link
     * EmbedClientX11#offer(Path)}.
     */
    void offer(Path hostSocketPath);

    /**
     * Same as {@link #offer(Path)}, but {@code wmClass} disambiguates which
     * top-level window gets offered — see {@link
     * EmbedClientX11#offer(Path, String)} (and {@link #announce(String)} for
     * the Win32 caveat).
     */
    void offer(Path hostSocketPath, String wmClass);

    /**
     * Registers a callback invoked once this window has been reparented into
     * an embedder, with the embedder's window id — see {@link
     * EmbedClientX11#onEmbedded}.
     */
    void onEmbedded(LongConsumer callback);

    /**
     * Registers a callback invoked when the embedding host detaches or dies
     * — see {@link EmbedClientX11#onHostDetached}.
     */
    void onHostDetached(Runnable callback);

    /**
     * Registers a callback invoked when this window gains ({@code true}) or
     * loses ({@code false}) input focus — see {@link
     * EmbedClientX11#onFocusChanged}.
     */
    void onFocusChanged(FocusListener callback);

    /**
     * Registers a callback invoked whenever this window's own size changes —
     * see {@link EmbedClientX11#onResized}.
     */
    void onResized(SizeListener callback);

    /**
     * Registers a callback invoked when the host signals this client is
     * shadowed by (or no longer shadowed by) a modal dialog — see {@link
     * EmbedClientX11#onModalityChanged}. Only fires for a client embedded via
     * {@link #offer(Path)} against a host's {@code listen} socket.
     */
    void onModalityChanged(ModalityListener callback);

    /**
     * Asks the host to give this window input focus once embedded — see
     * {@link EmbedClientX11#requestFocus()}. No-op if not currently embedded.
     */
    void requestFocus();

    /** The embedder's window id last reported to {@link #onEmbedded}, or -1 if not currently embedded. */
    long embedderWindowId();

    /**
     * Overrides how long resolving this process's own top-level window is
     * allowed to take before giving up. Defaults to 5 seconds.
     */
    void setWindowLookupTimeout(Duration timeout);

    /** Stops watching for host death and releases this client's resources. */
    @Override
    void close();
}
