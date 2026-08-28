package cz.loplex.xembed.core.x11;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.AtomByReference;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Reads format-32 (CARD32/Window/Atom) window properties such as
 * {@code _NET_CLIENT_LIST} and {@code _NET_WM_PID}.
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
}
