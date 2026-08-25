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

    /**
     * Adds {@code w} (which must belong to a different client) to this
     * connection's save-set, so that if this connection closes while
     * {@code w} is a descendant of a window this connection created, the X
     * server reparents {@code w} back to its closest surviving ancestor and
     * maps it instead of destroying it along with the rest of the subtree.
     * This is what makes host-death detection possible on the embedded
     * side: without it, an embedded window would simply be destroyed
     * alongside the embedder window when the host's connection goes away.
     */
    int XAddToSaveSet(Display display, Window w);

    /**
     * Removes {@code w} from this connection's save-set, undoing {@link
     * #XAddToSaveSet}. Needed before a host voluntarily reparents an
     * embedded client away on purpose (as opposed to finding out about the
     * detach after the fact when its own connection closes): the save-set
     * membership otherwise outlives that manual reparent, and would still
     * be checked — against a window this connection no longer has any
     * claim to embedding — whenever this connection eventually closes for
     * real.
     */
    int XRemoveFromSaveSet(Display display, Window w);

    /**
     * ICCCM section 4.1.4's standard "withdraw" convenience function: unmaps
     * {@code w} and sends a synthetic {@code UnmapNotify} event to the root
     * window of {@code screenNumber} (with {@code SubstructureNotifyMask |
     * SubstructureRedirectMask}), which is the specific signal a window
     * manager needs to treat this as a deliberate client-driven withdrawal
     * rather than an ordinary unmap it might have caused itself (e.g.
     * iconification). Needed before {@link Reparenting#reparent} on a window
     * that is (or was until just now) an ordinary WM-managed top-level
     * window — without it, some window managers (confirmed with openbox)
     * react to the implicit unmap {@code XReparentWindow} itself performs on
     * a mapped window by re-adopting it back under their own decoration
     * frame right after our own reparent succeeds, since as far as the WM
     * can tell nothing yet told it to let go.
     */
    int XWithdrawWindow(Display display, Window w, int screenNumber);

    int XSetInputFocus(Display display, Window focus, int revertTo, NativeLong time);

    int XGetInputFocus(Display display, WindowByReference focusReturn, IntByReference revertToReturn);

    /**
     * Must be the very first Xlib call made in the process to have any
     * effect; makes it safe for multiple threads to use Xlib afterward
     * (including sharing a single {@code Display} connection across
     * threads), which the {@code xembed-core.xembed.XEmbedInboundWatcher}
     * background thread relies on. {@link X11Display#open} calls this once,
     * unconditionally, before its first {@code XOpenDisplay}.
     */
    int XInitThreads();

    /**
     * Overload of {@code XCreateWindow} taking {@link RawWindowAttributes}
     * instead of JNA's bundled {@code XSetWindowAttributes} — see that
     * class's Javadoc for why.
     */
    Window XCreateWindow(Display display, Window parent, int x, int y, int width, int height, int borderWidth,
            int depth, int windowClass, Visual visual, NativeLong valueMask, RawWindowAttributes attributes);
}
