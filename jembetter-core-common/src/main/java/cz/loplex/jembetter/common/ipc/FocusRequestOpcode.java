package cz.loplex.jembetter.common.ipc;

/**
 * The wire format for a focus-request message on the Win32 control channel:
 * {@code EmbedClientWin32#requestFocus()} (in {@code jembetter-client})
 * writes it, {@code EmbedSocketWin32}'s per-client control-channel reader (in
 * {@code jembetter-host}) reads it back off the same {@link
 * java.nio.channels.SocketChannel} {@code EmbedSocketWin32#listen} keeps open
 * for the life of an embed. Client-to-host, the opposite direction from
 * {@link ModalityOpcode} on the same channel — any byte read in this
 * direction means "please focus me"; there's no payload to encode/decode, so
 * this is just the marker value both sides agree on.
 */
public final class FocusRequestOpcode {

    private FocusRequestOpcode() {
    }

    public static final byte MARKER = 1;
}
