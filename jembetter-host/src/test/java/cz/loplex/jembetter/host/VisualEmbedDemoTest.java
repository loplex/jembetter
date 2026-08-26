package cz.loplex.jembetter.host;

import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedInfo;
import cz.loplex.jembetter.core.xembed.XEmbedInfoProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not a correctness test — {@link EmbedHostTest} and {@link EmbedSocketTest}
 * already cover the same mechanics with 10-100px throwaway windows that
 * blink by unattended in well under a second. This one exists purely to be
 * watched: a human-sized host/client pair with narrated {@code Thread.sleep}
 * pauses between each step, for someone running
 * {@code mvn test -Dtest.xserver=Xephyr -Dgroups=visual -Dtest.excludedGroups=}
 * (see README.md's "Running tests" section) to actually see the reparent,
 * the resize-follows-host behavior, and the crash-detection teardown happen
 * live instead of inferring them from assertions.
 *
 * <p>Excluded from the default test run via the root pom's
 * {@code test.excludedGroups} property - plain {@code mvn test}/{@code
 * install} never touches this.
 */
@Tag("visual")
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class VisualEmbedDemoTest {

    private JFrame owner;
    private EmbedHost host;
    private Process clientProcess;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (host != null) {
            host.close();
        }
        if (owner != null) {
            owner.dispose();
        }
        if (clientProcess != null) {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void watchAHostAndClientEmbedResizeAndDetach() throws IOException, InterruptedException {
        say("Opening the host window...");
        Canvas placeholder = new Canvas();
        placeholder.setBackground(Color.DARK_GRAY);
        placeholder.setPreferredSize(new Dimension(500, 400));

        owner = new JFrame("VisualEmbedDemoTest host");
        owner.add(new JLabel("Host window (unrelated to the embed area)", SwingConstants.CENTER), BorderLayout.NORTH);
        owner.add(placeholder, BorderLayout.CENTER);
        owner.pack();
        owner.setLocation(80, 80);
        owner.setVisible(true);
        pause(1500);

        host = EmbedHost.create(placeholder);
        CountDownLatch detached = new CountDownLatch(1);
        host.onDetached(detached::countDown);

        say("Spawning the client process...");
        clientProcess = startClientProcess();
        long clientPid = clientProcess.pid();
        try (X11Display display = X11Display.open(null)) {
            long clientWindowId = waitForOwnWindow(display, clientPid);
            XEmbedInfoProperty.write(display.raw(), clientWindowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }
        pause(1000);

        say("Embedding the client into the host canvas - watch it jump into the placeholder area...");
        host.embed(clientPid);
        pause(2000);

        say("Shrinking the host frame - the embedded client should follow it down automatically...");
        owner.setSize(owner.getWidth() - 150, owner.getHeight() - 120);
        pause(2000);

        say("Killing the client process - watch the placeholder go back to plain dark gray...");
        clientProcess.destroy();
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onDetached never fired after the client process died");
        clientProcess = null;
        pause(1000);

        say("Done.");
    }

    private static void say(String message) {
        System.out.println("[VisualEmbedDemoTest] " + message);
    }

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static Process startClientProcess() throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder processBuilder = new ProcessBuilder(javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                VisualClientProcessMain.class.getName());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }

    private static long waitForOwnWindow(X11Display display, long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Client window never became visible to the window manager");
    }
}
