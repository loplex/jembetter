package cz.loplex.jembetter.common;

/**
 * Callback invoked when the embedding host's own top-level window is
 * activated ({@code true}) or deactivated ({@code false}) — i.e. becomes, or
 * stops being, the frontmost application window. The delivered counterpart of
 * XEmbed's {@code XEMBED_WINDOW_ACTIVATE}/{@code XEMBED_WINDOW_DEACTIVATE},
 * carried over the host&harr;client control channel (see {@code
 * cz.loplex.jembetter.common.ipc.ControlMessage}) rather than as a {@code
 * ClientMessage} the client can't read.
 *
 * <p>Distinct from {@link FocusListener}: that is <em>this window's</em> own
 * input focus; this is <em>host-level</em> activation, which stays meaningful
 * even while the embedded client itself holds the input focus within an
 * active host. X11 backend only for now — see {@code
 * jembetter-client.EmbedClient#onActivationChanged}; {@code EmbedClientWin32}
 * has no sender on the Win32 side to pair with yet.
 */
@FunctionalInterface
public interface ActivationListener {

    void activationChanged(boolean active);
}
