package cz.loplex.jembetter.client;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * {@link EmbedPlug}'s X11 implementation, via {@link EmbedClient} — see
 * {@link EmbedPlugWin32} for the Win32 counterpart {@link EmbedPlug#create}
 * dispatches to instead on Windows.
 */
final class EmbedPlugX11 implements EmbedPlug {

    private final EmbedClient client = new EmbedClient();

    @Override
    public void announce(String wmClass) {
        client.announce(wmClass);
    }

    @Override
    public void announce(Path hostSocket, String wmClass) {
        client.offer(hostSocket, wmClass);
    }

    @Override
    public void onEmbedded(LongConsumer callback) {
        client.onEmbedded(callback);
    }

    @Override
    public void onHostDetached(Runnable callback) {
        client.onHostDetached(callback);
    }

    @Override
    public void close() {
        client.close();
    }
}
