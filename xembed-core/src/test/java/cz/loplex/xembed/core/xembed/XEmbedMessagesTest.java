package cz.loplex.xembed.core.xembed;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XClientMessageEvent;
import com.sun.jna.platform.unix.X11.XEvent;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.x11.X11Ext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link XEmbedMessages#send} against a real X server: creates a
 * bare X11 window, sends it an XEMBED_EMBEDDED_NOTIFY message and reads the
 * ClientMessage back off the wire to check the encoding round-trips.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class XEmbedMessagesTest {

    private X11Display display;
    private Window window;

    @BeforeEach
    void createWindow() {
        display = X11Display.open(null);
        window = X11Ext.INSTANCE.XCreateSimpleWindow(display.raw(), display.defaultRootWindow(), 0, 0, 10, 10, 0, 0,
                0);
    }

    @AfterEach
    void destroyWindow() {
        X11Ext.INSTANCE.XDestroyWindow(display.raw(), window);
        display.close();
    }

    @Test
    void encodesAndDeliversEmbeddedNotify() {
        long embedderWindowId = 0xCAFEL;
        XEmbedMessages.send(display.raw(), window.longValue(), XEmbedMessage.EMBEDDED_NOTIFY, 0, embedderWindowId,
                XEmbedInfo.PROTOCOL_VERSION);

        XEvent received = new XEvent();
        assertTrue(waitForClientMessage(received), "XEMBED_EMBEDDED_NOTIFY was never delivered");

        received.setType(XClientMessageEvent.class);
        received.read();
        NativeLong[] data = received.xclient.data.l;
        assertEquals(XEmbedMessage.EMBEDDED_NOTIFY, XEmbedMessage.fromOpcode(data[1].longValue()));
        assertEquals(embedderWindowId, data[3].longValue());
        assertEquals(XEmbedInfo.PROTOCOL_VERSION, data[4].longValue());
    }

    private boolean waitForClientMessage(XEvent eventReturn) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (X11Ext.INSTANCE.XCheckTypedWindowEvent(display.raw(), window, X11Ext.ClientMessage, eventReturn)) {
                return true;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }
}
