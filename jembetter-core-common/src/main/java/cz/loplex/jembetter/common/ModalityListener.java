package cz.loplex.jembetter.common;

/**
 * Callback invoked when a client is told it's shadowed by ({@code true}) or
 * no longer shadowed by ({@code false}) a modal dialog. Delivered over the
 * host&harr;client control channel (see {@code
 * cz.loplex.jembetter.common.ipc.ControlMessage}) on both backends: {@code
 * jembetter-host}'s {@code EmbedSocket#setModal(boolean)} / {@code
 * EmbedSocketWin32#setModal(boolean)} send it, {@code jembetter-client}'s
 * {@code EmbedClient#onModalityChanged} / {@code
 * EmbedClientWin32#onModalityChanged} receive it. The X11 {@code setModal}
 * also still sends the XEmbed {@code XEMBED_MODALITY_ON}/{@code OFF} {@code
 * ClientMessage} as a courtesy to a genuinely XEmbed-aware external toolkit
 * (Win32 has no XEmbed equivalent); on both backends this callback is the
 * path that actually reaches a jembetter client.
 */
@FunctionalInterface
public interface ModalityListener {

    void modalityChanged(boolean modal);
}
