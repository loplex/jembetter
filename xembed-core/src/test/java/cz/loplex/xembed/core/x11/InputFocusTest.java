package cz.loplex.xembed.core.x11;

import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.WindowByReference;
import com.sun.jna.ptr.IntByReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class InputFocusTest {

    private X11Display display;
    private Window window;

    @BeforeEach
    void createMappedWindow() {
        display = X11Display.open(null);
        window = X11Ext.INSTANCE.XCreateSimpleWindow(display.raw(), display.defaultRootWindow(), 0, 0, 10, 10, 0, 0,
                0);
        X11Ext.INSTANCE.XMapWindow(display.raw(), window);
    }

    @AfterEach
    void destroyWindow() {
        X11Ext.INSTANCE.XDestroyWindow(display.raw(), window);
        display.close();
    }

    @Test
    void setsGlobalInputFocusToTheGivenWindow() {
        // XSetInputFocus requires the window to be viewable; a freshly
        // mapped top-level window only becomes viewable once the window
        // manager has acted on it, which is asynchronous.
        assertTrue(waitUntilViewable(), "window never became viewable");

        InputFocus.set(display, window.longValue());

        WindowByReference focusReturn = new WindowByReference();
        IntByReference revertToReturn = new IntByReference();
        X11Ext.INSTANCE.XGetInputFocus(display.raw(), focusReturn, revertToReturn);

        assertEquals(window.longValue(), focusReturn.getValue().longValue());
    }

    private boolean waitUntilViewable() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (WindowTree.isMapped(display, window.longValue())) {
                return true;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        return false;
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
