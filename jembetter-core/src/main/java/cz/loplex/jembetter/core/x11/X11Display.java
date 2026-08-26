package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Owns a connection to an X11 display, opened via {@code XOpenDisplay}.
 */
public final class X11Display implements AutoCloseable {

    private static volatile boolean threadsInitialized = false;

    private final Display display;

    private X11Display(Display display) {
        this.display = display;
    }

    /**
     * Opens the display named by {@code name}, or the one named by the
     * {@code DISPLAY} environment variable when {@code name} is {@code null}.
     */
    public static X11Display open(String name) {
        ensureThreadsInitialized();
        X11ErrorHandler.install();
        Display display = X11Ext.INSTANCE.XOpenDisplay(name);
        if (display == null) {
            throw new IllegalStateException(
                    "Could not open X11 display: " + (name != null ? name : System.getenv("DISPLAY")));
        }
        return new X11Display(display);
    }

    public Display raw() {
        return display;
    }

    public Window defaultRootWindow() {
        return X11Ext.INSTANCE.XDefaultRootWindow(display);
    }

    @Override
    public void close() {
        X11Ext.INSTANCE.XCloseDisplay(display);
    }

    // Must run before the process's first XOpenDisplay to have any effect;
    // lets a single Display connection be shared safely across threads,
    // which the socket window's own connection needs to do (it must be read
    // from the same connection that created the window, but also carries
    // ordinary calls like moveResize from whatever thread the AWT/Swing
    // component listener driving it runs on).
    private static synchronized void ensureThreadsInitialized() {
        if (!threadsInitialized) {
            X11Ext.INSTANCE.XInitThreads();
            threadsInitialized = true;
        }
    }
}
