package cz.loplex.xembed.core.x11;

import com.sun.jna.NativeLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class X11ExtTest {

    /**
     * Resolves symbols without calling them: Xlib functions dereference the
     * Display pointer without a null check, so invoking them without a live
     * X connection would crash the JVM rather than fail the test.
     */
    @Test
    void resolvesSymbolsAddedBeyondBaseJnaX11Binding() {
        assertNotNull(X11Ext.INSTANCE);

        NativeLibrary library = NativeLibrary.getInstance("X11");
        assertDoesNotThrow(() -> library.getFunction("XReparentWindow"));
        assertDoesNotThrow(() -> library.getFunction("XSetInputFocus"));
        assertDoesNotThrow(() -> library.getFunction("XGetInputFocus"));
    }
}
