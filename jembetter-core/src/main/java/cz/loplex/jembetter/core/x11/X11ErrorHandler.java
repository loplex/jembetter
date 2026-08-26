package cz.loplex.jembetter.core.x11;

import com.sun.jna.Native;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.XErrorEvent;
import com.sun.jna.platform.unix.X11.XErrorHandler;

/**
 * Installs a process-wide Xlib error handler that logs and returns instead
 * of Xlib's default behavior, which is to call {@code exit()} on any X
 * protocol error — silently killing the whole JVM, including unrelated
 * Swing/AWT state, over something as recoverable as a {@code BadWindow} from
 * a racy window lookup.
 *
 * <p>{@code XSetErrorHandler} is process-global, not per-{@code Display}, so
 * this only needs to be installed once regardless of how many
 * {@link X11Display} connections are open.
 */
public final class X11ErrorHandler {

    // Kept as a static field: JNA does not root native callbacks on its own,
    // so a GC'd handler here would crash the JVM the next time Xlib called
    // into it.
    private static final XErrorHandler HANDLER = X11ErrorHandler::handle;
    private static volatile boolean installed = false;

    private X11ErrorHandler() {
    }

    /** Installs the handler if it isn't already installed. Safe to call repeatedly. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        X11Ext.INSTANCE.XSetErrorHandler(HANDLER);
        installed = true;
    }

    private static int handle(Display display, XErrorEvent event) {
        byte[] buffer = new byte[256];
        X11Ext.INSTANCE.XGetErrorText(display, event.error_code, buffer, buffer.length);
        System.err.println("[jembetter] X11 error: " + Native.toString(buffer) + " (error_code="
                + (event.error_code & 0xFF) + ", request_code=" + (event.request_code & 0xFF) + ", minor_code="
                + (event.minor_code & 0xFF) + ", resourceid=" + event.resourceid.longValue() + ")");
        return 0;
    }
}
