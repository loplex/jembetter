package cz.loplex.xembed.core.xembed;

/**
 * Message types carried in the {@code data[1]} field of an {@code _XEMBED}
 * ClientMessage, as defined by the XEmbed Protocol Specification 0.5.
 */
public enum XEmbedMessage {

    EMBEDDED_NOTIFY(0),
    WINDOW_ACTIVATE(1),
    WINDOW_DEACTIVATE(2),
    REQUEST_FOCUS(3),
    FOCUS_IN(4),
    FOCUS_OUT(5),
    FOCUS_NEXT(6),
    FOCUS_PREV(7),
    MODALITY_ON(10),
    MODALITY_OFF(11),
    REGISTER_ACCELERATOR(12),
    UNREGISTER_ACCELERATOR(13),
    ACTIVATE_ACCELERATOR(14);

    public final int opcode;

    XEmbedMessage(int opcode) {
        this.opcode = opcode;
    }

    public static XEmbedMessage fromOpcode(long opcode) {
        for (XEmbedMessage message : values()) {
            if (message.opcode == opcode) {
                return message;
            }
        }
        throw new IllegalArgumentException("Unknown XEmbed message opcode: " + opcode);
    }
}
