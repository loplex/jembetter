package cz.loplex.jembetter.core.x11;

/** Callback invoked with a window's new width/height in pixels — see {@link WindowConfigureWatcher}. */
@FunctionalInterface
public interface SizeListener {

    void resized(int width, int height);
}
