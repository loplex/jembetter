package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms {@link ButtonGrab} actually intercepts a real {@code
 * ButtonPress} delivered through the X server, not just a synthetic
 * in-process event — the exact gap a previous click-to-focus attempt (a
 * plain {@code MouseListener}) fell into: it passed against a synthetic
 * {@code MouseEvent} dispatched in-process and then never fired for a real
 * click at all. This fires a real click via {@code xdotool}, the same way
 * that finding was confirmed live.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class ButtonGrabTest {

    private X11Display display;
    private long windowId = -1;

    @AfterEach
    void cleanup() {
        if (windowId >= 0) {
            RawWindow.destroy(display, windowId);
        }
        if (display != null) {
            display.close();
        }
    }

    @Test
    void interceptsARealClickDeliveredThroughTheXServer() throws IOException, InterruptedException {
        display = X11Display.open(null);
        windowId = RawWindow.createOverrideRedirect(display, 100, 100, 50, 50);
        assertTrue(waitUntilViewable(), "window never became viewable");

        ButtonGrab.install(display, windowId);
        try {
            xdotoolClick(125, 125);

            assertTrue(waitForButtonPress(), "grabbed ButtonPress from a real click never arrived");
        } finally {
            ButtonGrab.replay(display);
            ButtonGrab.uninstall(display, windowId);
        }
    }

    private boolean waitForButtonPress() {
        XEvent event = new XEvent();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            boolean pending;
            synchronized (X11Display.GLOBAL_LOCK) {
                pending = X11Ext.INSTANCE.XCheckTypedWindowEvent(display.raw(), new Window(windowId),
                        X11Ext.ButtonPress, event);
            }
            if (pending) {
                return true;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        return false;
    }

    private boolean waitUntilViewable() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (WindowTree.isMapped(display, windowId)) {
                return true;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void xdotoolClick(int screenX, int screenY) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("xdotool", "mousemove", Integer.toString(screenX),
                Integer.toString(screenY), "click", "1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "xdotool never finished");
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
