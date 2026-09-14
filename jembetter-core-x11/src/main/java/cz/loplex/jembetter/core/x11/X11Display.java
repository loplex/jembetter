package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;

import java.util.function.Consumer;
import java.util.function.Function;

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
 * single-threaded connection in tests) must do the same at the call site —
 * or, when the connection can be closed out from under that call, go
 * through {@link #ifOpen(Consumer)} instead, which takes the lock and
 * checks the connection is still open as one indivisible step.
 * This only serializes Xlib calls jembetter itself makes against each
 * other; it cannot serialize against whatever AWT's own X11 backend is
 * doing concurrently on its own connection, which is outside this
 * library's control.
 *
 * <p><strong>Use after close:</strong> a native call against a connection
 * {@code XCloseDisplay} has already freed crashes the JVM rather than
 * throwing, so no call this library makes is allowed to reach one. The three
 * methods below are how that is enforced, and every helper in this package
 * goes through one of them:
 *
 * <ul>
 *   <li>{@link #ifOpen(Consumer)} — a command, such as moving a window or
 *       setting focus. A closed connection means it is silently skipped:
 *       there is nothing left to command and nothing for a caller to do
 *       about it.
 *   <li>{@link #ifOpen(Function, Object)} — a call whose result is needed
 *       and for which "the connection is gone" has a correct answer the
 *       caller can name. Polling for a pending event is the case this
 *       exists for: no connection means no event.
 *   <li>{@link #requireOpen(Function)} — a query whose answer cannot be
 *       faked, which throws instead.
 * </ul>
 */
public final class X11Display implements AutoCloseable {

    /** Guards every native Xlib call this library makes, across every {@link X11Display} connection — see the class Javadoc. */
    public static final Object GLOBAL_LOCK = new Object();

    private static volatile boolean threadsInitialized = false;

    private final Display display;
    /** Guarded by {@link #GLOBAL_LOCK} — the same lock every native call takes, which is what makes {@link #ifOpen} work. */
    private boolean closed = false;

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

    /**
     * This connection's native {@code Display*}, for the two helpers that
     * take one directly ({@link
     * cz.loplex.jembetter.core.xembed.XEmbedMessages}, {@link
     * cz.loplex.jembetter.core.xembed.XEmbedInfoProperty}, both of which are
     * also used against a caller's own connection in tests). A caller
     * reaching for this takes on both obligations the methods below
     * otherwise handle: holding {@link #GLOBAL_LOCK} across the call, and
     * not making it at all once this connection has been closed.
     */
    public Display raw() {
        return display;
    }

    public Window defaultRootWindow() {
        return requireOpen(raw -> X11Ext.INSTANCE.XDefaultRootWindow(raw));
    }

    /**
     * Runs {@code action} against this connection's native {@code Display*}
     * under {@link #GLOBAL_LOCK}, or does nothing at all if the connection
     * has already been {@link #close() closed}.
     *
     * <p>This is how a caller that reaches for {@link #raw()} from a thread
     * that may still be running when another thread closes the connection —
     * an AWT listener callback, a watcher loop, a public method racing a
     * teardown — must make its native call. Unregistering such a callback
     * before closing is not enough on its own: one that has already started
     * keeps running, and since it takes the same lock {@code close()} does,
     * it can be parked on that monitor at the very moment the {@code
     * Display*} is freed and then call into freed memory. That is a JVM
     * crash inside native Xlib, not a catchable exception. Checking the flag
     * under the lock that guards the call leaves only two possible
     * orderings: the whole call runs before the close, or it does not run.
     */
    public void ifOpen(Consumer<Display> action) {
        synchronized (GLOBAL_LOCK) {
            if (closed) {
                return;
            }
            action.accept(display);
        }
    }

    /**
     * Runs {@code action} and returns its result, or returns {@code
     * valueIfClosed} without running it at all if the connection has already
     * been closed — {@link #ifOpen(Consumer)} for a call whose result the
     * caller needs, and for which the caller can name a value that correctly
     * means "there is no connection any more".
     */
    public <T> T ifOpen(Function<Display, T> action, T valueIfClosed) {
        synchronized (GLOBAL_LOCK) {
            if (closed) {
                return valueIfClosed;
            }
            return action.apply(display);
        }
    }

    /**
     * Runs {@code action} and returns its result, or throws {@link
     * IllegalStateException} if the connection has already been closed — for
     * a query with no such value to fall back on. Most queries here have
     * none that is not also a real answer: {@link WindowTree#parentOf}
     * returning 0 reads as "reparented to the desktop", and quietly handing
     * that back is worse than failing. Throwing keeps the failure inside
     * Java, where a caller racing a teardown can catch it, instead of in
     * native Xlib, where it would take the process down.
     */
    public <T> T requireOpen(Function<Display, T> action) {
        synchronized (GLOBAL_LOCK) {
            if (closed) {
                throw new IllegalStateException("X11 display connection is already closed");
            }
            return action.apply(display);
        }
    }

    /** Closes the connection. Idempotent, and every later {@link #ifOpen(Consumer)} is a no-op. */
    @Override
    public void close() {
        synchronized (GLOBAL_LOCK) {
            if (closed) {
                return;
            }
            closed = true;
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
