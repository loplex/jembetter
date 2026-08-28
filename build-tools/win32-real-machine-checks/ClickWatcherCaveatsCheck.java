package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.core.win32.Win32ClickWatcher;

import com.sun.jna.platform.win32.WinDef.RECT;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-machine check for the {@link Win32ClickWatcher} caveats Wine cannot
 * reproduce and {@code Win32ClickWatcherTest} therefore cannot cover:
 *
 * <ul>
 *   <li><b>hook survival under load (automatic PASS/FAIL):</b> a {@code
 *       WH_MOUSE_LL} proc that overruns {@code LowLevelHooksTimeout} too
 *       often is silently unhooked by Windows. {@code Win32ClickWatcher}
 *       offloads callback work to a dispatch thread specifically to stay
 *       under that budget; this fires a burst of clicks into a watched
 *       window and checks nearly all still reach the callback.</li>
 *   <li><b>added system-wide mouse latency (observational):</b> while the
 *       hook is installed, every mouse event round-trips through this
 *       process. Times a fixed burst of raw mouse-move events with the
 *       watcher installed vs. not and prints both — no automatic verdict.</li>
 *   <li><b>UIPI (observational):</b> a medium-integrity hook cannot see
 *       input over a higher-integrity (elevated) window. Genuinely testing
 *       this needs an elevated target process, which an unattended CI runner
 *       normally can't spawn; this just records whether the environment
 *       allowed the check at all.</li>
 * </ul>
 *
 * <p>No AWT and no {@code --add-opens} needed — all windows here are plain
 * {@code STATIC}-class HWNDs (see {@code CheckWindows}).
 */
final class ClickWatcherCaveatsCheck {

    private static final int BURST = 40;

    private ClickWatcherCaveatsCheck() {
    }

    public static void main(String[] args) throws Exception {
        boolean survival = hookSurvivalUnderLoad();
        latencyDelta();
        uipiObservation();

        System.out.println("CLICK: " + (survival ? "PASS" : "FAIL")
                + " (hook-survival=" + survival
                + "; latency + UIPI are observational - read the lines above)");
        System.exit(survival ? 0 : 1);
    }

    /** hook-survival: fire BURST clicks into a watched window; almost all must reach the callback. */
    private static boolean hookSurvivalUnderLoad() throws Exception {
        long hwnd = CheckWindows.createVisibleTopLevelWindowAt("click-a", 100, 100, 300, 220);
        AtomicInteger hits = new AtomicInteger();
        try (Win32ClickWatcher watcher = new Win32ClickWatcher()) {
            watcher.watch(hwnd, hits::incrementAndGet);
            RECT r = CheckWindows.rectOf(hwnd);
            int cx = (r.left + r.right) / 2;
            int cy = (r.top + r.bottom) / 2;
            for (int i = 0; i < BURST; i++) {
                CheckWindows.clickAt(cx, cy);
                Thread.sleep(20);
            }
            // Give the dispatch thread a moment to drain.
            Thread.sleep(500);
        } finally {
            CheckWindows.destroyWindow(hwnd);
        }
        int got = hits.get();
        boolean ok = got >= (int) Math.ceil(BURST * 0.8);
        System.out.println("CLICK/hook-survival: " + got + "/" + BURST + " injected clicks reached the callback"
                + " (pass threshold " + (int) Math.ceil(BURST * 0.8) + ") - "
                + (ok ? "hook stayed alive under load" : "hook likely hit LowLevelHooksTimeout and was dropped"));
        return ok;
    }

    /** latency: time a raw mouse-move burst with vs. without the watcher installed. */
    private static void latencyDelta() throws Exception {
        long hwnd = CheckWindows.createVisibleTopLevelWindowAt("click-b", 100, 100, 200, 150);
        try {
            long withoutNanos = timeMouseMoveBurst();

            long withNanos;
            try (Win32ClickWatcher watcher = new Win32ClickWatcher()) {
                watcher.watch(hwnd, () -> { });
                withNanos = timeMouseMoveBurst();
            }

            double withoutMs = withoutNanos / 1_000_000.0;
            double withMs = withNanos / 1_000_000.0;
            System.out.printf("CLICK/latency: %d mouse-move events took %.1fms with no hook, %.1fms with "
                    + "Win32ClickWatcher installed (delta %.1fms, %.2fus/event) - "
                    + "observational, no automatic verdict%n",
                    2000, withoutMs, withMs, withMs - withoutMs,
                    (withNanos - withoutNanos) / 1000.0 / 2000);
        } finally {
            CheckWindows.destroyWindow(hwnd);
        }
    }

    private static long timeMouseMoveBurst() {
        long start = System.nanoTime();
        CheckWindows.spamMouseMoves(2000);
        return System.nanoTime() - start;
    }

    /** UIPI: can this environment even mount the UIPI check? */
    private static void uipiObservation() {
        boolean elevated = isProbablyElevated();
        System.out.println("CLICK/UIPI: this process " + (elevated ? "IS" : "is NOT")
                + " elevated. UIPI blocks a lower-integrity WH_MOUSE_LL hook from seeing input "
                + "over a higher-integrity window; a real check needs an elevated target process "
                + "on the other side. " + (elevated
                        ? "Running elevated here means the hook would sit ABOVE most targets - "
                          + "the blocking direction can't be exercised. "
                        : "No elevated target is spawned on an unattended runner. ")
                + "UIPI remains reasoned-about, not verified - see Win32ClickWatcher's Javadoc.");
    }

    private static boolean isProbablyElevated() {
        // Cheap heuristic: only an elevated (or SYSTEM) process can write here.
        java.io.File probe = new java.io.File(
                System.getenv("SystemRoot") + "\\Temp\\jembetter-win32check-elev-probe.tmp");
        try {
            if (probe.createNewFile()) {
                probe.delete();
                return true;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }
}
