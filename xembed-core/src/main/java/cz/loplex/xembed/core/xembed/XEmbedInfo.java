package cz.loplex.xembed.core.xembed;

/**
 * Layout and flag values of the {@value XEmbedAtoms#XEMBED_INFO} window
 * property (XEmbed Protocol Specification 0.5, section 4): two CARD32 words,
 * {@code version} followed by a {@code flags} bitfield.
 */
public final class XEmbedInfo {

    public static final int PROTOCOL_VERSION = 0;

    /** The client should be mapped; the embedder tracks this via PropertyNotify. */
    public static final long MAPPED = 1L;

    private XEmbedInfo() {
    }
}
