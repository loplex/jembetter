package cz.loplex.jembetter.common;

/**
 * Callback invoked when a window gains ({@code true}) or loses ({@code
 * false}) input focus — see {@code jembetter-core-x11}'s {@code
 * WindowFocusWatcher}. Platform-neutral in shape, but only the X11 backend
 * currently fires it — see {@code EmbedPlug#onFocusChanged}'s Javadoc.
 */
@FunctionalInterface
public interface FocusListener {

    void focusChanged(boolean focused);
}
