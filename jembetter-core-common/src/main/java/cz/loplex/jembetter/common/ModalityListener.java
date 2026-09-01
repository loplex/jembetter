package cz.loplex.jembetter.common;

/**
 * Callback invoked when a client is told it's shadowed by ({@code true}) or
 * no longer shadowed by ({@code false}) a modal dialog — see {@code
 * jembetter-host}'s {@code EmbedSocketWin32#setModal(boolean)} (the sender)
 * and {@code jembetter-client}'s {@code EmbedClientWin32#onModalityChanged}
 * (the receiver). Win32-only for now: X11's own {@code
 * EmbedSocket#setModal(boolean)} sends {@code XEMBED_MODALITY_ON}/{@code OFF}
 * but has no receiving side either — see {@code docs/win32-status.md}.
 */
@FunctionalInterface
public interface ModalityListener {

    void modalityChanged(boolean modal);
}
