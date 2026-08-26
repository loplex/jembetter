package cz.loplex.jembetter.common.ipc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PidHandshakeTest {

    @Test
    void roundTripsThePidOverAUnixDomainSocket() throws Exception {
        Path socketPath = Files.createTempFile("xembed-test-", ".sock");
        Files.delete(socketPath); // bind() requires the path not to exist yet
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);

            CompletableFuture<Long> received = CompletableFuture.supplyAsync(() -> {
                try (SocketChannel accepted = server.accept()) {
                    return PidHandshake.receive(accepted);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(address);
                PidHandshake.send(client, 424242L);
            }

            assertEquals(424242L, received.get(5, TimeUnit.SECONDS));
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }
}
