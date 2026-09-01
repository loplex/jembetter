package cz.loplex.jembetter.core.x11;

import com.sun.jna.NativeLong;
import com.sun.jna.Structure;
import com.sun.jna.platform.unix.X11.Colormap;
import com.sun.jna.platform.unix.X11.Cursor;
import com.sun.jna.platform.unix.X11.Pixmap;

import java.util.List;

/**
 * Mirrors Xlib's {@code XSetWindowAttributes} field-for-field, except
 * {@code override_redirect} (and {@code save_under}, kept only to preserve
 * layout) are declared {@code int} rather than {@code boolean}: JNA's
 * bundled X11 binding marshals a Java {@code boolean} {@code Structure}
 * field as {@code -1} for {@code true}, but the X11 wire protocol requires
 * a value-list {@code BOOL} to be exactly 0 or 1 — sending {@code -1} gets
 * {@code XCreateWindow}/{@code XChangeWindowAttributes} rejected with
 * {@code BadValue} (confirmed against a live X server; this codebase hit
 * the same JNA boolean-marshalling issue once before).
 *
 * <p>Every field is declared, in the original struct's order, purely so
 * this struct's computed size and field offsets — in particular
 * {@code override_redirect}'s — line up with the real one; only
 * {@code override_redirect} is actually read or written by this codebase.
 */
public final class RawWindowAttributes extends Structure {

    public Pixmap background_pixmap;
    public NativeLong background_pixel;
    public Pixmap border_pixmap;
    public NativeLong border_pixel;
    public int bit_gravity;
    public int win_gravity;
    public int backing_store;
    public NativeLong backing_planes;
    public NativeLong backing_pixel;
    public int save_under;
    public NativeLong event_mask;
    public NativeLong do_not_propagate_mask;
    public int override_redirect;
    public Colormap colormap;
    public Cursor cursor;

    @Override
    protected List<String> getFieldOrder() {
        return List.of("background_pixmap", "background_pixel", "border_pixmap", "border_pixel", "bit_gravity",
                "win_gravity", "backing_store", "backing_planes", "backing_pixel", "save_under", "event_mask",
                "do_not_propagate_mask", "override_redirect", "colormap", "cursor");
    }
}
