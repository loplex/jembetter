package cz.loplex.jembetter.common.ipc;

/**
 * The wire format for a modality message on the Win32 control channel:
 * {@code EmbedSocketWin32#setModal(boolean)} (in {@code jembetter-host})
 * writes it, {@code EmbedClientWin32#onModalityChanged} (in {@code
 * jembetter-client}) reads it back off the same {@link
 * java.nio.channels.SocketChannel} {@code EmbedSocketWin32#listen} keeps open
 * for the life of an embed. A single byte per message — 1 for modal-on, 0 for
 * modal-off — with no length prefix or message-type tag, since this is
 * currently the only kind of message the channel ever carries besides the
 * initial {@link PidHandshake}.
 */
public final class ModalityOpcode {

    private ModalityOpcode() {
    }

    public static byte encode(boolean modal) {
        return (byte) (modal ? 1 : 0);
    }

    public static boolean decode(byte opcode) {
        return opcode != 0;
    }
}
