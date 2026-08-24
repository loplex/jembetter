package cz.loplex.xembed.core.xembed;

/**
 * Detail values carried in {@code data[2]} of a {@link XEmbedMessage#FOCUS_IN}
 * ClientMessage, as defined by the XEmbed Protocol Specification 0.5.
 */
public final class XEmbedFocus {

    public static final long CURRENT = 0;
    public static final long FIRST = 1;
    public static final long LAST = 2;

    private XEmbedFocus() {
    }
}
