package cz.loplex.xembed.client;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Simplified 1:1 client-side facade over {@link EmbedClient}, matching
 * {@code xembed-host.EmbedHost} on the other side. Composed entirely from
 * {@link EmbedClient}; nothing here re-implements X11 handling of its own.
 */
public interface EmbedPlug extends AutoCloseable {

    /** Creates a plug backed by this process's default X11 client implementation. */
    static EmbedPlug create() {
        return new EmbedPlugX11();
    }

    /**
     * Does everything needed to become embeddable except dial a host
     * socket, for a host that already knows this process's pid directly —
     * see {@link EmbedClient#announce(String)}. Pass {@code null} for a
     * process with a single top-level window.
     */
    void announce(String wmClass);

    /**
     * Hands this process's pid to the host listening at {@code hostSocket}
     * so it can look this process's window up and reparent it — see {@link
     * EmbedClient#offer(Path, String)}. Pass {@code null} for {@code
     * wmClass} for a process with a single top-level window.
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

    /** Stops watching for host death. */
    @Override
    void close();
}
