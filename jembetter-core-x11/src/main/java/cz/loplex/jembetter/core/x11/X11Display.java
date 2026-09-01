package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

/**
 * Owns a connection to an X11 display, opened via {@code XOpenDisplay}.
 *
 * <p><strong>Thread-safety contract:</strong> {@code XInitThreads()} (see
 * {@link #ensureThreadsInitialized}) cannot be relied on to make Xlib safe
 * for concurrent use from multiple threads in practice — it has no effect
 * unless it is the very first Xlib call the process ever makes, but any
 * realistic AWT/Swing host has already driven its own X11 connection
 * through AWT's toolkit init long before this class's first {@link #open}
 * call runs. Without it actually taking effect, Xlib is documented as
 * unsafe for concurrent multi-threaded use even across <em>different</em>
 * {@code Display} connections opened by the same process, not just a
 * single connection shared across threads — confirmed here by direct
 * observation: garbage was still read back from {@code XErrorEvent}s
 * (implausible {@code resourceid}s, non-standard error codes) even after
 * every caller sharing one connection across threads was made to
 * {@code synchronized} around it, as long as some other, wholly separate
 * connection (e.g. a background watcher's own) was still being driven
 * concurrently.
 *
 * <p>Every native Xlib call this library makes therefore synchronizes on
 * {@link #GLOBAL_LOCK}, a single process-wide lock — not one lock per
 * connection — regardless of which {@code Display} it targets: every
 * helper in this package that accepts an {@code X11Display} does this
 * internally already; a caller reaching for {@link #raw()} directly (e.g.
 * to call {@link cz.loplex.jembetter.core.xembed.XEmbedMessages} or
 * {@link cz.loplex.jembetter.core.xembed.XEmbedInfoProperty}, which take a
 * raw {@code Display} since they're also used against a caller's own
 * single-threaded connection in tests) must do the same at the call site.
 * This only serializes Xlib calls jembetter itself makes against each
 * other; it cannot serialize against whatever AWT's own X11 backend is
 * doing concurrently on its own connection, which is outside this
 * library's control.
 */
public final class X11Display implements AutoCloseable {

    /** Guards every native Xlib call this library makes, across every {@link X11Display} connection — see the class Javadoc. */
    public static final Object GLOBAL_LOCK = new Object();

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
        Display display;
        synchronized (GLOBAL_LOCK) {
            display = X11Ext.INSTANCE.XOpenDisplay(name);
        }
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
        synchronized (GLOBAL_LOCK) {
            return X11Ext.INSTANCE.XDefaultRootWindow(display);
        }
    }

    @Override
    public void close() {
        synchronized (GLOBAL_LOCK) {
            X11Ext.INSTANCE.XCloseDisplay(display);
        }
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
