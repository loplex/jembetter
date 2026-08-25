package cz.loplex.xembed.common;

import java.awt.Canvas;
import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Extracts a realized (displayable) AWT {@link Canvas}'s own native window
 * handle — an X11 window id on Linux, an HWND on Windows — so a client
 * process's top-level window can be reparented into it directly, instead of
 * kept visually aligned over it as a separate top-level window.
 *
 * <p>{@code Component.getPeer()} was removed in JDK 25, so this reflects on
 * the package-private {@code Component.peer} field instead, then on the
 * peer's own OS-specific accessor ({@code getWindow()} on X11, {@code
 * getHWnd()} on Windows) — both are JDK-internal, unsupported APIs.
 *
 * <p>Requires the JVM to be started with {@code --add-opens
 * java.desktop/java.awt=ALL-UNNAMED} (X11 also needs {@code --add-opens
 * java.desktop/sun.awt.X11=ALL-UNNAMED}; Windows needs {@code --add-opens
 * java.desktop/sun.awt.windows=ALL-UNNAMED} instead) — without it, {@code
 * setAccessible(true)} below throws {@code InaccessibleObjectException}.
 */
public final class CanvasNativeHandle {

    private CanvasNativeHandle() {
    }

    public static long extract(Canvas canvas) {
        if (!canvas.isDisplayable()) {
            throw new IllegalStateException("Canvas has no peer - it must be displayable first");
        }
        try {
            Field peerField = Component.class.getDeclaredField("peer");
            peerField.setAccessible(true);
            Object peer = peerField.get(canvas);

            String methodName = isWindows() ? "getHWnd" : "getWindow";
            Method accessor = peer.getClass().getMethod(methodName);
            accessor.setAccessible(true);
            return (long) accessor.invoke(peer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not extract Canvas's native window handle", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
