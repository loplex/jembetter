package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.ipc.PidHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedClientWin32} against a hand-rolled fake host — a raw
 * {@link ServerSocketChannel} playing exactly the part {@code
 * EmbedSocketWin32#listen} plays (accept, read the pid handshake, keep the
 * channel open, write a {@code ModalityOpcode}-encoded byte) — rather than
 * pulling in {@code jembetter-host} itself, which this module doesn't depend
 * on. Tagged {@code windows} like the rest of this module's Win32-backend
 * tests, even though the mechanism under test (a plain {@code AF_UNIX}
 * socket) has no Win32-specific API calls in it — it exists specifically to
 * pair with {@code EmbedSocketWin32}'s own control channel.
 */
@Tag("windows")
class EmbedClientWin32Test {

    private ServerSocketChannel server;
    private Path socketPath;
    private EmbedClientWin32 client;

    @AfterEach
    void cleanup() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (socketPath != null) {
            Files.deleteIfExists(socketPath);
        }
    }

    @Test
    void receivesTheModalityOpcodesTheHostWrites() throws Exception {
        socketPath = Files.createTempFile("jembetter-client-win32-modal-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        CountDownLatch pidReceived = new CountDownLatch(1);
        AtomicBoolean firstOpcode = new AtomicBoolean();
        AtomicBoolean secondOpcode = new AtomicBoolean();
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);

        Thread fakeHost = new Thread(() -> {
            try (SocketChannel accepted = server.accept()) {
                PidHandshake.receive(accepted);
                pidReceived.countDown();

                writeByte(accepted, (byte) 1);
                Thread.sleep(200); // give the reader a chance to dispatch before the next byte
                writeByte(accepted, (byte) 0);
                Thread.sleep(500); // keep the channel open past the last assertion
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "fake-host");
        fakeHost.setDaemon(true);
        fakeHost.start();

        client = new EmbedClientWin32();
        client.onModalityChanged(modal -> {
            if (firstDelivered.getCount() > 0) {
                firstOpcode.set(modal);
                firstDelivered.countDown();
            } else {
                secondOpcode.set(modal);
                secondDelivered.countDown();
            }
        });
        client.connect(socketPath);

        assertTrue(pidReceived.await(5, TimeUnit.SECONDS), "the fake host never received the pid handshake");
        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS), "onModalityChanged never fired for the first opcode");
        assertTrue(firstOpcode.get(), "the first opcode (1) was not decoded as modal=true");
        assertTrue(secondDelivered.await(5, TimeUnit.SECONDS), "onModalityChanged never fired for the second opcode");
        assertEquals(false, secondOpcode.get(), "the second opcode (0) was not decoded as modal=false");
    }

    @Test
    void connectSendsThePidHandshake() throws Exception {
        socketPath = Files.createTempFile("jembetter-client-win32-handshake-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        long expectedPid = ProcessHandle.current().pid();
        CompletableFuture<Long> received = CompletableFuture.supplyAsync(() -> {
            try (SocketChannel accepted = server.accept()) {
                return PidHandshake.receive(accepted);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        client = new EmbedClientWin32();
        client.connect(socketPath);

        assertEquals(expectedPid, received.get(5, TimeUnit.SECONDS), "connect() did not send this process's own pid");
    }

    private static void writeByte(SocketChannel channel, byte value) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] { value });
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
