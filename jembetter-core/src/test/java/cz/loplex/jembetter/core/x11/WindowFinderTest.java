package cz.loplex.jembetter.core.x11;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.Frame;
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
        frame = new Frame("jembetter-core WindowFinderTest");
        frame.setSize(50, 50);
        frame.setVisible(true);

        long pid = ProcessHandle.current().pid();
        List<Long> found = pollUntilNonEmptyOrTimeout(pid);

        assertFalse(found.isEmpty(), "window manager never published this process's window in _NET_CLIENT_LIST");
    }

    @Test
    void findsWindowByPidAndWmClass() throws InterruptedException {
        frame = new Frame("jembetter-core WindowFinderTest");
        frame.setSize(50, 50);
        frame.setVisible(true);

        long pid = ProcessHandle.current().pid();
        List<Long> found = pollUntilNonEmptyOrTimeout(pid);
        assertFalse(found.isEmpty(), "window manager never published this process's window in _NET_CLIENT_LIST");
        long windowId = found.get(0);

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
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return found;
    }
}
