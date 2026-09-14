package cz.loplex.jembetter.core.x11;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.AtomByReference;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads format-32 (CARD32/Window/Atom) window properties such as
 * {@code _NET_CLIENT_LIST} and {@code _NET_WM_PID}, and format-8 (STRING
 * list) properties such as {@code WM_CLASS}.
 *
 * <p>Xlib returns format-32 property data padded to the platform's native
 * {@code long} width rather than packed as 32-bit values, even though the
 * wire protocol carries 32 bits per item — a long-standing R6 compatibility
 * wart — hence the {@link Native#LONG_SIZE} branch below.
 */
public final class X11Properties {

    private static final NativeLong READ_ALL = new NativeLong(0xFFFFFFFFL);

    private X11Properties() {
    }

    public static long[] readCardinal32(Display display, Window window, Atom property) {
        AtomByReference actualType = new AtomByReference();
        IntByReference actualFormat = new IntByReference();
        NativeLongByReference nitems = new NativeLongByReference();
        NativeLongByReference bytesAfter = new NativeLongByReference();
        PointerByReference propReturn = new PointerByReference();

        int status = X11Ext.INSTANCE.XGetWindowProperty(display, window, property,
                new NativeLong(0), READ_ALL, false, new Atom(X11Ext.AnyPropertyType),
                actualType, actualFormat, nitems, bytesAfter, propReturn);

        Pointer data = propReturn.getValue();
        if (status != 0 || data == null || nitems.getValue().longValue() == 0) {
            return new long[0];
        }

        try {
            int count = (int) nitems.getValue().longValue();
            if (Native.LONG_SIZE == 8) {
                return data.getLongArray(0, count);
            }
            int[] ints = data.getIntArray(0, count);
            long[] result = new long[count];
            for (int i = 0; i < count; i++) {
                result[i] = ints[i] & 0xFFFFFFFFL;
            }
            return result;
        } finally {
            X11Ext.INSTANCE.XFree(data);
        }
    }

    /**
     * Reads a format-8 property holding one or more NUL-separated strings,
     * such as {@code WM_CLASS} (instance name, then class name), decoded
     * according to the type the property actually turned out to have — see
     * {@link #charsetFor}.
     */
    public static List<String> readStringList8(Display display, Window window, Atom property) {
        AtomByReference actualType = new AtomByReference();
        IntByReference actualFormat = new IntByReference();
        NativeLongByReference nitems = new NativeLongByReference();
        NativeLongByReference bytesAfter = new NativeLongByReference();
        PointerByReference propReturn = new PointerByReference();

        int status = X11Ext.INSTANCE.XGetWindowProperty(display, window, property,
                new NativeLong(0), READ_ALL, false, new Atom(X11Ext.AnyPropertyType),
                actualType, actualFormat, nitems, bytesAfter, propReturn);

        Pointer data = propReturn.getValue();
        if (status != 0 || data == null || nitems.getValue().longValue() == 0) {
            return List.of();
        }

        try {
            Charset charset = charsetFor(actualType.getValue());
            int count = (int) nitems.getValue().longValue();
            byte[] bytes = data.getByteArray(0, count);
            List<String> result = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    result.add(new String(bytes, start, i - start, charset));
                    start = i + 1;
                }
            }
            return result;
        } finally {
            X11Ext.INSTANCE.XFree(data);
        }
    }
    /**
     * The encoding a format-8 property's bytes are in, which ICCCM ties to
     * the property's <em>type</em> rather than to any global convention:
     * {@code STRING} is ISO 8859-1, {@code UTF8_STRING} is UTF-8.
     *
     * <p>This used to read everything as UTF-8. For {@code WM_CLASS}, whose
     * type is {@code STRING}, that turns any byte at or above 0x80 into
     * U+FFFD — so a class name with an accent in it silently matches
     * nothing, and the host reports the client as never having published a
     * window, which points at the wrong thing entirely.
     *
     * <p>Everything other than {@code STRING} falls back to UTF-8 rather
     * than to a failure. That covers {@code UTF8_STRING} correctly, and it
     * is the better guess for a toolkit that ignores the spec: writing UTF-8
     * bytes into a {@code STRING} property is common enough in practice that
     * treating an unrecognised type as Latin-1 would lose more than it
     * gained.
     */
    private static Charset charsetFor(Atom actualType) {
        if (actualType != null && actualType.longValue() == X11.XA_STRING.longValue()) {
            return StandardCharsets.ISO_8859_1;
        }
        return StandardCharsets.UTF_8;
    }

}
