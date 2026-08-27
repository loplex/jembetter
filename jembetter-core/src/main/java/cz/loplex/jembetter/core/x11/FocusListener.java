package cz.loplex.jembetter.core.x11;

/** Callback invoked when a window gains ({@code true}) or loses ({@code false}) X input focus — see {@link WindowFocusWatcher}. */
@FunctionalInterface
public interface FocusListener {

    void focusChanged(boolean focused);
}
