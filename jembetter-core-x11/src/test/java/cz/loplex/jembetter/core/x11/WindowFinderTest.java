package cz.loplex.jembetter.core.x11;

import com.sun.jna.Memory;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.unix.X11.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.Frame;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link WindowFinder} against a real window manager: opens an
 * actual AWT window and looks it up by this JVM's own PID, the same way a
 * host or client would locate its own top-level window.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class WindowFinderTest {

    private Frame frame;
    private X11Display display;

    @BeforeEach
    void openDisplay() {
        display = X11Display.open(null);
    }

    @AfterEach
    void cleanup() {
        if (frame != null) {
            frame.dispose();
        }
        display.close();
    }

    @Test
    void findsOwnTopLevelWindowByPid() throws InterruptedException {
        frame = new Frame("jembetter-core-x11 WindowFinderTest");
        frame.setSize(50, 50);
        frame.setVisible(true);

        long pid = ProcessHandle.current().pid();
        List<Long> found = pollUntilNonEmptyOrTimeout(pid);

        assertFalse(found.isEmpty(), "window manager never published this process's window in _NET_CLIENT_LIST");
    }

    @Test
    void findsWindowByPidAndWmClass() throws InterruptedException {
        frame = new Frame("jembetter-core-x11 WindowFinderTest");
        frame.setSize(50, 50);
        frame.setVisible(true);

        long pid = ProcessHandle.current().pid();
        List<Long> found = pollUntilNonEmptyOrTimeout(pid);
        assertFalse(found.isEmpty(), "window manager never published this process's window in _NET_CLIENT_LIST");
        long windowId = found.getFirst();

        Optional<String> wmClass = WindowFinder.readWmClass(display, windowId);
        assertTrue(wmClass.isPresent(), "AWT window has no WM_CLASS property");

        List<Long> matched = WindowFinder.findTopLevelWindowsByPidAndClass(display, pid, wmClass.get());
        assertEquals(List.of(windowId), matched);

        List<Long> unmatched = WindowFinder.findTopLevelWindowsByPidAndClass(display, pid, "no-such-class");
        assertTrue(unmatched.isEmpty());
    }

    /**
     * The window manager reparents and publishes {@code _NET_CLIENT_LIST}
     * asynchronously after the window is mapped, so a single immediate
     * lookup is racy.
     */
    private List<Long> pollUntilNonEmptyOrTimeout(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!found.isEmpty()) {
                return found;
            }
            //noinspection BusyWait
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return found;
    }
    /**
     * {@code WM_CLASS} has X11 type {@code STRING}, which ICCCM defines as
     * ISO 8859-1, and this used to be read as UTF-8 regardless. The
     * e-acute below is a single byte (0xE9) in Latin-1 and is not valid
     * UTF-8 on its own, so the old read produced U+FFFD — and a host
     * filtering on that class name would then find nothing and report the
     * client as never having published a window at all.
     */
    @Test
    void readsAWmClassInTheEncodingItsTypeDeclares() {
        long windowId = RawWindow.createOverrideRedirect(display, 0, 0, 10, 10);
        try {
            byte[] wmClass = "app\u0000caf\u00e9\u0000".getBytes(StandardCharsets.ISO_8859_1);
            Memory data = new Memory(wmClass.length);
            data.write(0, wmClass, 0, wmClass.length);
            synchronized (X11Display.GLOBAL_LOCK) {
                X11Ext.INSTANCE.XChangeProperty(display.raw(), new Window(windowId), X11.XA_WM_CLASS,
                        X11.XA_STRING, 8, X11Ext.PropModeReplace, data, wmClass.length);
                X11Ext.INSTANCE.XSync(display.raw(), false);
            }

            assertEquals(Optional.of("caf\u00e9"), WindowFinder.readWmClass(display, windowId));
        } finally {
            RawWindow.destroy(display, windowId);
        }
    }

}
