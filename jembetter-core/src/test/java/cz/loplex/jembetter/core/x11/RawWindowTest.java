package cz.loplex.jembetter.core.x11;

import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XWindowAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class RawWindowTest {

    @Test
    void createsAMappedOverrideRedirectWindowAtTheGivenBounds() {
        try (X11Display display = X11Display.open(null)) {
            long windowId = RawWindow.createOverrideRedirect(display, 10, 20, 100, 80);
            try {
                XWindowAttributes attributes = new XWindowAttributes();
                X11Ext.INSTANCE.XGetWindowAttributes(display.raw(), new Window(windowId), attributes);

                // Regression check for the JNA boolean-marshalling issue
                // RawWindowAttributes works around (see its Javadoc):
                // without that workaround, XCreateWindow itself would have
                // failed with a BadValue X protocol error before we even
                // get here.
                assertTrue(attributes.override_redirect, "window was not created override-redirect");
                assertEquals(10, attributes.x);
                assertEquals(20, attributes.y);
                assertEquals(100, attributes.width);
                assertEquals(80, attributes.height);
                assertEquals(X11Ext.IsViewable, attributes.map_state);
            } finally {
                RawWindow.destroy(display, windowId);
            }
        }
    }
}
