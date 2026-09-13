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
 * The one X11-only capability — {@link
 * EmbedClientX11#onActivationChanged} (host-window activation; Win32's host
 * has no sender to pair with) — lives on {@link EmbedClientX11} only; a
 * caller that needs it downcasts to that class explicitly.
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
     * Starts watching this process's own already-known top-level window for
     * {@link #onEmbedded}/{@link #onHostDetached}/{@link
     * #onFocusChanged}/{@link #onResized} — everything {@link #announce()}
     * does except resolving the window, for a toolkit-opaque client that
     * already holds its own native window handle (a JavaFX {@code Stage}, a
     * GTK window) and hands it to the host out-of-band rather than through
     * either handshake. The host embeds such a window with {@code
     * EmbedHost#embedOpaque(long)}/{@code EmbedSocket#embedOpaque(long)}.
     * See {@link EmbedClientX11#watchOwnWindow} and {@link
     * EmbedClientWin32#watchOwnWindow} for each backend's specifics.
     *
     * <p>No socket is opened on this path, so {@link #onModalityChanged}
     * (and X11's {@code onActivationChanged}) never fire for a client
     * embedded this way — the same as on the {@link #announce()} path.
     */
    void watchOwnWindow(long windowId);

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
     *
     * <p><b>Being embedded is itself a size change</b>, so a client that
     * registered this before the embed is called for the embed as well as for
     * every resize afterwards. Treat the callback as "the window is now this
     * size", not as "the host has finished deciding": act on the latest
     * values rather than on the first call, and make the work idempotent
     * rather than assuming one call per intent. A layout driven from the
     * first call after an embed can be laying out against a size that is
     * about to change again.
     *
     * <p>How many calls arrive is not part of the contract and differs by
     * backend — Win32 reparenting also strips the window's decorations, which
     * moves the client area on its own. The values are always truthful; their
     * number and timing are not something to depend on.
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
