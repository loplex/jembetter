package cz.loplex.xembed.core.xembed;

import com.sun.jna.NativeLong;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.XClientMessageEvent;
import com.sun.jna.platform.unix.X11.XEvent;
import cz.loplex.xembed.core.x11.X11Ext;

/**
 * Sends {@code _XEMBED} ClientMessages (XEmbed Protocol Specification 0.5,
 * section 3, "Generic Event Structure"): format-32 ClientMessages delivered
 * directly to the target window, whose first three data longs carry a
 * timestamp, the message opcode and a message-specific detail code, and
 * whose remaining two longs carry message-specific data.
 */
public final class XEmbedMessages {

    private XEmbedMessages() {
    }

    public static void send(Display display, long targetWindowId, XEmbedMessage message, long detail, long data1,
            long data2) {
        Window target = new Window(targetWindowId);
        Atom xembedAtom = X11Ext.INSTANCE.XInternAtom(display, XEmbedAtoms.XEMBED, false);

        XEvent event = new XEvent();
        event.setType(XClientMessageEvent.class);
        event.xclient.type = X11Ext.ClientMessage;
        event.xclient.serial = new NativeLong(0L);
        event.xclient.send_event = 1;
        event.xclient.display = display;
        event.xclient.window = target;
        event.xclient.message_type = xembedAtom;
        event.xclient.format = 32;
        event.xclient.data.setType(NativeLong[].class);
        event.xclient.data.l[0] = new NativeLong(0L); // CurrentTime; best-effort, XEmbed doesn't require a real one here
        event.xclient.data.l[1] = new NativeLong(message.opcode);
        event.xclient.data.l[2] = new NativeLong(detail);
        event.xclient.data.l[3] = new NativeLong(data1);
        event.xclient.data.l[4] = new NativeLong(data2);

        // event_mask 0: deliver directly to the client owning the window,
        // bypassing that window's own XSelectInput mask (ClientMessage events
        // are never selectable via a mask).
        X11Ext.INSTANCE.XSendEvent(display, target, 0, new NativeLong(0L), event);
        X11Ext.INSTANCE.XFlush(display);
    }
}
