package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class X11ErrorHandlerTest {

    @Test
    void logsAndSurvivesAnXProtocolErrorInsteadOfExitingTheJvm() {
        try (X11Display display = X11Display.open(null)) {
            // X11Display.open() already installs the handler; calling again
            // here documents that a bogus window id below is expected to
            // produce a recoverable BadWindow, not a JVM exit.
            X11ErrorHandler.install();

            PrintStream originalErr = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                // A window id far outside any plausible XID range the
                // server could have allocated triggers a protocol error
                // (observed as BadDrawable/BadWindow depending on the
                // server); low ids like 1 aren't reliably invalid, since
                // some servers reserve small XIDs for their own resources.
                X11Ext.INSTANCE.XSetInputFocus(display.raw(), new Window(0xdeadbeefL), X11Ext.RevertToParent,
                        new NativeLong(X11Ext.CurrentTime));
                X11Ext.INSTANCE.XSync(display.raw(), false);
            } finally {
                System.setErr(originalErr);
            }

            String logged = captured.toString(StandardCharsets.UTF_8);
            assertTrue(logged.contains("X11 error:"), "expected the error handler to log; got: " + logged);
        }
    }
}
