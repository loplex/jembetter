package cz.loplex.jembetter.common;

/** Callback invoked with a window's new width/height in pixels. */
@FunctionalInterface
public interface SizeListener {

    void resized(int width, int height);
}
