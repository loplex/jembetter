package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32ReparentWatcher;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import cz.loplex.jembetter.core.win32.Win32WindowGeometry;

import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Real-machine check for {@link Win32ReparentWatcher}, in the cross-process +
 * real-DWM setup {@code EmbedPlugWin32} actually uses it in — beyond what
 * {@code Win32ReparentWatcherTest}'s same-process HWNDs cover. Three
 * transitions against a separate-JVM client window, all automatic PASS/FAIL:
 *
 * <ol>
 *   <li><b>embed:</b> host reparents the client window under a {@code Canvas}
 *       HWND → watcher must fire with the new parent.</li>
 *   <li><b>host detach:</b> host releases it back to the desktop → watcher
 *       must fire with 0.</li>
 *   <li><b>destroy asymmetry:</b> host re-embeds it, then disposes the
 *       Canvas' top-level frame → on Win32 that destroys the embedded child
 *       outright (unlike X11, where a released child survives its former
 *       parent). Watcher must fire with 0 and the client HWND must be gone.</li>
 * </ol>
 *
 * <p>Requires {@code --add-opens java.desktop/java.awt=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED} (see {@code
 * CanvasNativeHandle}) — {@code run.ps1} passes those.
 */
final class ReparentWatcherCheck {

    private ReparentWatcherCheck() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("REPARENT host");
        Canvas placeholder = new Canvas();
        placeholder.setSize(320, 240);
        host.add(placeholder, BorderLayout.CENTER);
        host.pack();
        host.setVisible(true);
        long canvasHwnd = CanvasNativeHandle.extract(placeholder);
        System.out.println("REPARENT: host Canvas HWND=0x" + Long.toHexString(canvasHwnd));

        Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                "cz.loplex.jembetter.win32check.ChildWindowMain", "reparent-child")
                .redirectErrorStream(true)
                .start();
        long childPid = child.pid();
        awaitReady(child.getInputStream(), childPid);

        long clientHwnd = pollForWindow(childPid, 5000);
        if (clientHwnd == 0) {
            fail(child, "never found the child's top-level window via Win32WindowFinder (pid=" + childPid + ")");
            return;
        }
        System.out.println("REPARENT: found child HWND=0x" + Long.toHexString(clientHwnd));

        List<Long> observed = new CopyOnWriteArrayList<>();
        boolean q1;
        boolean q2;
        boolean q3;
        try (Win32ReparentWatcher watcher = new Win32ReparentWatcher()) {
            watcher.watch(clientHwnd, newParent -> {
                observed.add(newParent);
                System.out.println("REPARENT: watcher fired - newParent=0x" + Long.toHexString(newParent));
            });

            // (1) embed
            Win32Reparent.reparent(clientHwnd, canvasHwnd, 0, 0);
            Win32WindowGeometry.moveResize(clientHwnd, 0, 0, placeholder.getWidth(), placeholder.getHeight());
            q1 = pollUntil(() -> observed.contains(canvasHwnd), 3000);
            System.out.println("REPARENT: (1) embed observed=" + q1);

            // (2) host detach - release back to the desktop
            Win32Reparent.release(clientHwnd, 200, 200);
            q2 = pollUntil(() -> lastOf(observed) != null && lastOf(observed) == 0L, 3000);
            System.out.println("REPARENT: (2) host-detach observed=" + q2);

            // (3) destroy asymmetry - re-embed, then destroy the parent frame.
            // Wait until the WATCHER itself has observed the re-embed (not
            // just our own GetParent call) so its lastKnownParent is the
            // canvas HWND before we destroy it - otherwise a fast
            // embed-then-destroy races past the 50ms poll and the 0 is never
            // a *change* from the watcher's point of view.
            Win32Reparent.reparent(clientHwnd, canvasHwnd, 0, 0);
            boolean watcherSawReEmbed = pollUntil(
                    () -> lastOf(observed) != null && lastOf(observed) == canvasHwnd, 3000);
            int markerBeforeDestroy = observed.size();
            host.dispose();
            boolean clientGone = pollUntil(() -> !CheckWindows.isWindow(clientHwnd), 3000);
            boolean firedZero = pollUntil(
                    () -> observed.size() > markerBeforeDestroy
                            && observed.get(observed.size() - 1) == 0L, 3000);
            q3 = watcherSawReEmbed && clientGone && firedZero;
            System.out.println("REPARENT: (3) destroy asymmetry - watcherSawReEmbed=" + watcherSawReEmbed
                    + " clientHwndGone=" + clientGone + " watcherFiredZero=" + firedZero);
        }

        endChild(child);

        boolean passed = q1 && q2 && q3;
        System.out.println("REPARENT: " + (passed ? "PASS" : "FAIL")
                + " (embed=" + q1 + ", hostDetach=" + q2 + ", destroyAsymmetry=" + q3 + ")");
        System.exit(passed ? 0 : 1);
    }

    private static Long lastOf(List<Long> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
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

    private static boolean pollUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
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
            // the child's window may already be gone (transition 3)
        }
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }

    private static void fail(Process child, String reason) {
        System.out.println("REPARENT: FAIL - " + reason);
        child.destroyForcibly();
        System.exit(1);
    }
}
