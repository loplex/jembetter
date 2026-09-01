package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32FocusWatcher;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Real-machine check for {@link Win32FocusWatcher}: the one caveat {@code
 * docs/win32-status.md} still lists as unconfirmed — real {@code
 * EVENT_OBJECT_FOCUS} delivery. Wine's {@code SetWinEventHook} emulation
 * never delivers one at all, so {@code Win32FocusWatcherTest}'s
 * event-delivery cases are {@code @Tag("wine-incompatible")} and, until this
 * check runs, the mechanism has only been reasoned about by analogy with
 * {@code Win32ClickWatcher}'s confirmed {@code WH_MOUSE_LL} hook, never
 * actually spiked.
 *
 * <p>Two gated checks, both against a watcher installed in <em>this</em>
 * process (the hook is system-wide, so which process installs it doesn't
 * matter for delivery — see {@link Win32FocusWatcher}'s Javadoc):
 *
 * <ol>
 *   <li><b>same-process gain:</b> {@link Win32Focus#set} on a watched window
 *       owned by this process — the same shape {@code
 *       Win32FocusWatcherTest} exercises, just for the first time on real
 *       Windows instead of Wine.</li>
 *   <li><b>cross-process gain + loss:</b> {@code Win32Focus#set} on a
 *       separate JVM's window — the actual shape {@code EmbedPlugWin32}
 *       depends on: a host process's {@code AttachThreadInput}-based {@code
 *       SetFocus} reaching a client process's own watcher, and the
 *       previously-focused window correctly reported lost.</li>
 * </ol>
 *
 * <p>No AWT and no {@code --add-opens} needed for the host-side window (a
 * plain {@code STATIC}-class HWND, see {@code CheckWindows}); the
 * cross-process window is a separate-JVM {@link ChildWindowMain}, same as
 * {@link ReparentWatcherCheck} uses.
 */
final class FocusWatcherCheck {

    private FocusWatcherCheck() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        long hwndA = CheckWindows.createVisibleTopLevelWindowAt("focuswatch-a", 120, 120, 200, 150);

        Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                "cz.loplex.jembetter.win32check.ChildWindowMain", "focuswatch-child")
                .redirectErrorStream(true)
                .start();
        long childPid = child.pid();
        awaitReady(child.getInputStream(), childPid);

        long hwndB = pollForWindow(childPid, 5000);
        if (hwndB == 0) {
            System.out.println("FOCUSWATCH: FAIL - never found the child's top-level window via "
                    + "Win32WindowFinder (pid=" + childPid + ")");
            CheckWindows.destroyWindow(hwndA);
            child.destroyForcibly();
            System.exit(1);
            return;
        }
        System.out.println("FOCUSWATCH: host hwndA=0x" + Long.toHexString(hwndA)
                + ", child hwndB=0x" + Long.toHexString(hwndB) + " (pid=" + childPid + ")");

        List<String> events = new CopyOnWriteArrayList<>();
        boolean sameProcessGain;
        boolean crossProcessGain;
        boolean crossProcessLoss;
        try (Win32FocusWatcher watcher = new Win32FocusWatcher()) {
            watcher.watch(hwndA, focused -> events.add("A:" + focused));
            watcher.watch(hwndB, focused -> events.add("B:" + focused));

            // (1) same-process gain
            Win32Focus.set(hwndA);
            sameProcessGain = pollUntil(() -> events.contains("A:true"), 3000);
            System.out.println("FOCUSWATCH: (1) same-process gain observed=" + sameProcessGain);

            // (2) cross-process gain + loss - focus a separate JVM's window.
            Win32Focus.set(hwndB);
            crossProcessGain = pollUntil(() -> events.contains("B:true"), 3000);
            crossProcessLoss = pollUntil(() -> events.contains("A:false"), 3000);
            System.out.println("FOCUSWATCH: (2) cross-process gain(B)=" + crossProcessGain
                    + " loss(A)=" + crossProcessLoss);
        } finally {
            CheckWindows.destroyWindow(hwndA);
            endChild(child);
        }

        boolean passed = sameProcessGain && crossProcessGain && crossProcessLoss;
        System.out.println("FOCUSWATCH: " + (passed ? "PASS" : "FAIL")
                + " (sameProcessGain=" + sameProcessGain
                + ", crossProcessGain=" + crossProcessGain
                + ", crossProcessLoss=" + crossProcessLoss + ")");
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
            // the child's window may already be gone
        }
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }
}
