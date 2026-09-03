package cz.loplex.jembetter.common.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * The wire format for the host&harr;client <em>control channel</em>: the same
 * {@link java.nio.channels.SocketChannel} {@code EmbedSocket#listen} /{@code
 * EmbedSocketWin32#listen} accept the initial {@link PidHandshake} on, kept
 * open for the life of an embed instead of closed right afterward.
 *
 * <p>Every message is a fixed <strong>2-byte frame</strong>: a {@link Type}
 * code byte, then a single boolean payload byte (1 or 0). No length prefix,
 * no variable-size payload — every message this channel carries fits that
 * shape, so a reader always consumes exactly two bytes per message and never
 * has to frame by hand.
 *
 * <p>Replaces the earlier untyped single-byte {@code ModalityOpcode} (host&rarr;
 * client) and {@code FocusRequestOpcode} (client&rarr;host): once the host had
 * more than one kind of thing to say on the channel (modality
 * <em>and</em> host-window activation), a bare byte with no type tag was
 * ambiguous.
 *
 * <p>Which side sends which {@link Type} is a per-constant convention, not
 * enforced here — the channel itself is full-duplex.
 */
public final class ControlMessage {

    /** The kind of a {@link ControlMessage}; its {@code code} is the first byte on the wire. */
    public enum Type {

        /**
         * host&rarr;client: {@code flag} {@code true} = this client is now
         * shadowed by a modal dialog owned by the host, {@code false} = no
         * longer. The delivered counterpart of {@code XEMBED_MODALITY_ON}/
         * {@code XEMBED_MODALITY_OFF}.
         */
        MODALITY((byte) 1),

        /**
         * host&rarr;client: {@code flag} {@code true} = the host's own
         * top-level window was activated (became frontmost), {@code false} =
         * deactivated. The delivered counterpart of {@code
         * XEMBED_WINDOW_ACTIVATE}/{@code XEMBED_WINDOW_DEACTIVATE}. Distinct
         * from this window's own input focus (see {@code
         * cz.loplex.jembetter.common.FocusListener}).
         */
        ACTIVATION((byte) 2),

        /**
         * client&rarr;host: "please give this window input focus". {@code
         * flag} is unused (always {@code false}). Only the Win32 control
         * channel carries this — the X11 backend sends {@code
         * XEMBED_REQUEST_FOCUS} as a real {@code ClientMessage} the host can
         * actually read, so its channel is host&rarr;client only.
         */
        FOCUS_REQUEST((byte) 3);

        private final byte code;

        Type(byte code) {
            this.code = code;
        }

        /** The byte this type is encoded as, first of the 2-byte frame. */
        public byte code() {
            return code;
        }

        static Type ofCode(byte code) {
            for (Type type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown control-message type code: " + code);
        }
    }

    private final Type type;
    private final boolean flag;

    private ControlMessage(Type type, boolean flag) {
        this.type = type;
        this.flag = flag;
    }

    /** A message of {@code type} carrying {@code flag} as its payload. */
    public static ControlMessage of(Type type, boolean flag) {
        return new ControlMessage(type, flag);
    }

    /** A {@link Type#FOCUS_REQUEST} message (its payload byte is unused). */
    public static ControlMessage focusRequest() {
        return new ControlMessage(Type.FOCUS_REQUEST, false);
    }

    public Type type() {
        return type;
    }

    /** The boolean payload — meaning depends on {@link #type()}; unused for {@link Type#FOCUS_REQUEST}. */
    public boolean flag() {
        return flag;
    }

    /**
     * Writes this message's 2-byte frame to {@code channel}, retrying short
     * writes until both bytes are out.
     */
    public void writeTo(SocketChannel channel) throws IOException {
        ByteBuffer frame = ByteBuffer.wrap(toBytes());
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    /** The 2 bytes {@link #writeTo} would put on the wire: {@code [type code][flag]}. */
    public byte[] toBytes() {
        return new byte[] { type.code, (byte) (flag ? 1 : 0) };
    }

    /** Decodes a 2-byte frame (as produced by {@link #toBytes()}). */
    public static ControlMessage decode(byte[] frame) {
        if (frame.length != 2) {
            throw new IllegalArgumentException("A control frame is exactly 2 bytes, got " + frame.length);
        }
        return new ControlMessage(Type.ofCode(frame[0]), frame[1] != 0);
    }

    /**
     * Reads one 2-byte frame from {@code channel}, blocking until both bytes
     * arrive. Returns {@code null} if the peer closes the channel before a
     * full frame is read — a clean end of stream, not an error.
     */
    public static ControlMessage readFrom(SocketChannel channel) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(2);
        while (frame.hasRemaining()) {
            if (channel.read(frame) < 0) {
                return null;
            }
        }
        return decode(frame.array());
    }
}
