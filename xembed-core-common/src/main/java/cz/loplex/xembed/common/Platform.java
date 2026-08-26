package cz.loplex.xembed.common;

/**
 * The one {@code os.name} check every OS-dispatch point in this library
 * (host and client facades, {@link CanvasNativeHandle}) shares, so it's
 * spelled out in exactly one place.
 */
public final class Platform {

    private Platform() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
