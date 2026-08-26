package cz.loplex.jembetter.core.xembed;

import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import cz.loplex.jembetter.core.x11.X11Ext;
import cz.loplex.jembetter.core.x11.X11Properties;

import java.util.Optional;

/**
 * Reads/writes the {@value XEmbedAtoms#XEMBED_INFO} window property (XEmbed
 * Protocol Specification 0.5, section 4): two CARD32 words, {@code version}
 * followed by a {@code flags} bitfield.
 */
public final class XEmbedInfoProperty {

    private XEmbedInfoProperty() {
    }

    public record Value(int version, long flags) {

        public boolean mapped() {
            return (flags & XEmbedInfo.MAPPED) != 0;
        }
    }

    public static void write(Display display, long windowId, Value value) {
        Atom infoAtom = X11Ext.INSTANCE.XInternAtom(display, XEmbedAtoms.XEMBED_INFO, false);

        Memory data = new Memory(2L * NativeLong.SIZE);
        data.setNativeLong(0, new NativeLong(value.version()));
        data.setNativeLong(NativeLong.SIZE, new NativeLong(value.flags()));

        X11Ext.INSTANCE.XChangeProperty(display, new Window(windowId), infoAtom, infoAtom, 32,
                X11Ext.PropModeReplace, data, 2);
        X11Ext.INSTANCE.XFlush(display);
    }

    public static Optional<Value> read(Display display, long windowId) {
        Atom infoAtom = X11Ext.INSTANCE.XInternAtom(display, XEmbedAtoms.XEMBED_INFO, false);
        long[] words = X11Properties.readCardinal32(display, new Window(windowId), infoAtom);
        if (words.length < 2) {
            return Optional.empty();
        }
        return Optional.of(new Value((int) words[0], words[1]));
    }
}
