package cz.loplex.xembed.common.ipc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * The v0 handshake between host and client processes: the client sends
 * nothing but its own process id over a freshly connected channel, letting
 * the host locate the client's top-level window itself via
 * {@code WindowFinder} rather than trusting a self-reported window id.
 */
public final class PidHandshake {

    private PidHandshake() {
    }

    public static void send(SocketChannel channel, long pid) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).putLong(pid).flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static long receive(SocketChannel channel) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Peer closed the connection before completing the handshake");
                }
            }
            buffer.flip();
            return buffer.getLong();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
