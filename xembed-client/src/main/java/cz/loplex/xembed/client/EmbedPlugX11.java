package cz.loplex.xembed.client;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * {@link EmbedPlug}'s only implementation today — X11, via {@link
 * EmbedClient}. See {@code xembed-host.EmbedHostX11}'s Javadoc for why this
 * isn't yet OS-dispatched.
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
