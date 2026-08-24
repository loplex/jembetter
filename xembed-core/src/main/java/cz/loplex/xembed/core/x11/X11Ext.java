package cz.loplex.xembed.core.x11;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.ptr.IntByReference;

/**
 * Extends JNA's bundled {@link X11} binding with the Xlib functions it does not
 * declare but window reparenting and XEmbed focus handling need.
 */
public interface X11Ext extends X11 {

    X11Ext INSTANCE = Native.load("X11", X11Ext.class);

    int XReparentWindow(Display display, Window w, Window parent, int x, int y);

    int XSetInputFocus(Display display, Window focus, int revertTo, NativeLong time);

    int XGetInputFocus(Display display, WindowByReference focusReturn, IntByReference revertToReturn);
}
