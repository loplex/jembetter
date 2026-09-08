package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Gated on the OS rather than on {@code DISPLAY} like the rest of this
 * module's tests, because this one's requirement is genuinely different: it
 * needs {@code libX11} to exist, not an X server to connect to (see the test's
 * own comment). Skipping it wherever {@code DISPLAY} is unset would therefore
 * skip it on plenty of machines that can run it perfectly well - but leaving
 * it ungated made it the one x11 test that ran on the Windows CI job, where
 * {@code NativeLibrary.getInstance("X11")} cannot resolve anything and failed
 * the whole module.
 */
@EnabledOnOs(OS.LINUX)
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
        assertDoesNotThrow(() -> library.getFunction("XGrabButton"));
        assertDoesNotThrow(() -> library.getFunction("XUngrabButton"));
        assertDoesNotThrow(() -> library.getFunction("XAllowEvents"));
    }
}
