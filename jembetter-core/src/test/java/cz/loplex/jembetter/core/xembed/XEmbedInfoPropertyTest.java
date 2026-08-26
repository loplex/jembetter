package cz.loplex.jembetter.core.xembed;

import com.sun.jna.platform.unix.X11.Window;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.x11.X11Ext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class XEmbedInfoPropertyTest {

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
    void writtenValueRoundTrips() {
        XEmbedInfoProperty.Value written = new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION,
                XEmbedInfo.MAPPED);
        XEmbedInfoProperty.write(display.raw(), window.longValue(), written);

        Optional<XEmbedInfoProperty.Value> read = XEmbedInfoProperty.read(display.raw(), window.longValue());

        assertTrue(read.isPresent());
        assertEquals(written, read.get());
        assertTrue(read.get().mapped());
    }
}
