package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import cz.loplex.jembetter.host.EmbedHost;

import com.sun.jna.platform.win32.WinDef.RECT;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.Canvas;

/**
 * Real-machine check for click-to-focus <em>through the {@link EmbedHost}
 * facade</em>, against a genuinely separate-process embedded client — the
 * end-to-end outcome {@code EmbedHostWin32Test.clickIntoEmbeddedAreaDoesNotThrow}
 * deliberately stops short of (it only asserts the click doesn't throw
 * through the hook), and that {@code Win32ClickWatcher}'s own reactor tests
 * can't reach because Wine doesn't deliver {@code WH_MOUSE_LL} events for
 * real injected input against a real embedded child.
 *
 * <p>Gated PASS/FAIL:
 *
 * <ol>
 *   <li>after {@link EmbedHost#embed(long)} (which focuses the client
 *       itself), parking focus on an unrelated top-level window actually
 *       moves it off the embedded client;</li>
 *   <li>a real {@code SendInput} left-click into the embedded area's screen
 *       rect makes {@code Win32ClickWatcher}'s low-level mouse hook return
 *       keyboard focus to the client — verified with an independent {@code
 *       GetGUIThreadInfo} probe.</li>
 * </ol>
 *
 * <p>A {@code WS_CHILD} window reparented from another process does not get
 * keyboard focus from a bare click on its own (cross-process input queues
 * aren't attached — the same reason X11 needs a passive {@code XGrabButton}
 * for this), so a PASS here is attributable to {@code Win32ClickWatcher}.
 *
 * <p>Requires {@code --add-opens java.desktop/java.awt=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED} (host canvas HWND
 * extraction) — {@code run.ps1} passes those.
 */
final class ClickToFocusWin32Check {

    private ClickToFocusWin32Check() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("CLICKFOCUS host");
        Canvas canvas = new Canvas();
        canvas.setSize(320, 240);
        host.add(canvas, BorderLayout.CENTER);
        host.pack();
        host.setLocation(200, 200);
        host.setVisible(true);
        Thread.sleep(300);

        // An unrelated, non-overlapping top-level window to park focus on.
        long parkingWindow = CheckWindows.createVisibleTopLevelWindowAt(
                "clickfocus-parking", 900, 200, 200, 150);

        Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                "cz.loplex.jembetter.win32check.ChildWindowMain", "clickfocus-child")
                .redirectErrorStream(true)
                .start();
        long childPid = child.pid();
        awaitReady(child.getInputStream(), childPid);

        long clientHwnd = pollForWindow(childPid, 5000);
        if (clientHwnd == 0) {
            System.out.println("CLICKFOCUS: FAIL - never found the child's top-level window (pid=" + childPid + ")");
            CheckWindows.destroyWindow(parkingWindow);
            child.destroyForcibly();
            System.exit(1);
            return;
        }
        System.out.println("CLICKFOCUS: child pid=" + childPid + " hwnd=0x" + Long.toHexString(clientHwnd));

        boolean parkedOff;
        boolean focusReturned;
        try (EmbedHost embedHost = EmbedHost.create(canvas)) {
            embedHost.embed(childPid);

            Win32Focus.set(parkingWindow);
            parkedOff = pollUntil(() -> !CheckWindows.hasKeyboardFocus(clientHwnd), 3000);
            System.out.println("CLICKFOCUS: (1) focus parked off the embedded client => " + parkedOff);

            RECT rect = CheckWindows.rectOf(clientHwnd);
            int cx = (rect.left + rect.right) / 2;
            int cy = (rect.top + rect.bottom) / 2;
            System.out.println("CLICKFOCUS: injecting a left-click at the embedded area center (" + cx + "," + cy + ")");
            CheckWindows.clickAt(cx, cy);

            focusReturned = pollUntil(() -> CheckWindows.hasKeyboardFocus(clientHwnd), 4000);
            System.out.println("CLICKFOCUS: (2) after the click, GetGUIThreadInfo says the embedded window "
                    + (focusReturned ? "DOES" : "does NOT") + " hold keyboard focus");
        } finally {
            CheckWindows.destroyWindow(parkingWindow);
            endChild(child);
            host.dispose();
        }

        boolean passed = parkedOff && focusReturned;
        System.out.println("CLICKFOCUS: " + (passed ? "PASS" : "FAIL")
                + " (parkedOff=" + parkedOff + ", focusReturnedOnClick=" + focusReturned + ")");
        System.exit(passed ? 0 : 1);
    }

    private static void awaitReady(InputStream in, long expectedPid) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line = reader.readLine();
        if (line == null || !line.contains("pid=" + expectedPid)) {
            throw new IllegalStateException("child window did not report READY: " + line);
        }
    }

    private static long pollForWindow(long pid, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<Long> found = Win32WindowFinder.findTopLevelWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        }
        return 0;
    }

    private static boolean pollUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static void endChild(Process child) throws Exception {
        child.getOutputStream().write('\n');
        try {
            child.getOutputStream().flush();
        } catch (Exception ignored) {
            // the child's window may already be gone
        }
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }
}
