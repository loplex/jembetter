package cz.loplex.jembetter.win32check;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import cz.loplex.jembetter.host.EmbedHost;

import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Real-machine check: can a host grant focus to its embedded client while a
 * <em>third</em> process holds the foreground?
 *
 * <p><b>Observational</b> — no automatic verdict, and it never fails the run.
 * It exists to accumulate evidence about a question that currently has none.
 *
 * <p><b>The question.</b> {@link Win32Focus#set}'s foreground-lock fallback
 * attaches this thread's input queue to the thread of whatever window holds
 * the foreground, and makes both its grants there. {@code SetForegroundWindow}
 * wants exactly that thread. {@code SetFocus} documents a different
 * requirement — the target window must be attached to the calling thread's
 * queue — and for an embedded client the target belongs to the client
 * process's thread. Those two coincide whenever the foreground window is the
 * host's own frame, because a cross-thread parent/child relationship attaches
 * the client's queue to the frame's. When some other process holds the
 * foreground, they do not, and the reasoning says {@code SetFocus} should have
 * nothing to act on.
 *
 * <p><b>Why it is only a question.</b> Splitting the two grants so each
 * attaches to the thread its own call needs was tried on 2026-09-14 and
 * reverted: it broke click-to-focus outright, failing {@code
 * ClickToFocusWin32Check} 5 runs of 6 against 0 of 5 without it — detaching
 * from the target's queue appears to reset the focus just set on it. So the
 * contract reading survives, the obvious fix does not, and nobody knows
 * whether the gap it describes can be reached at all: every check here grants
 * focus while the host process itself is in the foreground, which is the case
 * where the single attachment happens to be enough.
 *
 * <p><b>What this prints.</b> With a client embedded and {@link
 * ForegroundStealerMain} holding the foreground from a third process, it calls
 * {@link EmbedHost#requestFocus()} and asks {@code GetGUIThreadInfo} whether
 * focus actually landed. A run where the steal did not take is reported as
 * such and says nothing either way — the point is the state, not the call.
 *
 * <p>Read the accumulated {@code BGFOCUS: grant landed=} lines across runs
 * before concluding anything. One run is a sample, and this whole entry exists
 * because a single sample was once taken for a verdict.
 *
 * <p>Requires {@code --add-opens java.desktop/java.awt=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED} (host canvas HWND
 * extraction) — {@code run.ps1} passes those.
 */
final class BackgroundFocusGrantCheck {

    private BackgroundFocusGrantCheck() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("BGFOCUS host");
        Canvas canvas = new Canvas();
        canvas.setSize(320, 240);
        host.add(canvas, BorderLayout.CENTER);
        host.pack();
        host.setLocation(200, 200);
        host.setVisible(true);
        Thread.sleep(300);
        System.out.println("BGFOCUS: host canvas HWND=0x"
                + Long.toHexString(CanvasNativeHandle.extract(canvas))
                + " pid=" + ProcessHandle.current().pid());

        Process child = new ProcessBuilder(javaBin, "-cp", classpath,
                "cz.loplex.jembetter.win32check.ChildWindowMain", "bgfocus-child")
                .redirectErrorStream(true)
                .start();
        long childPid = child.pid();
        awaitReady(child.getInputStream(), childPid);
        long clientHwnd = pollForWindow(childPid, 5000);
        if (clientHwnd == 0) {
            System.out.println("BGFOCUS: INCONCLUSIVE - never found the child's top-level window (pid="
                    + childPid + ")");
            child.destroyForcibly();
            host.dispose();
            return;
        }
        System.out.println("BGFOCUS: child pid=" + childPid + " hwnd=0x" + Long.toHexString(clientHwnd));

        Process stealer = null;
        try (EmbedHost embedHost = EmbedHost.create(canvas)) {
            embedHost.embed(childPid);
            // Embedding grants focus itself, so this says the baseline works
            // before anything is taken away.
            boolean focusedAfterEmbed = pollUntil(() -> CheckWindows.hasKeyboardFocus(clientHwnd), 3000);
            System.out.println("BGFOCUS: (1) embed's own focus grant landed => " + focusedAfterEmbed);

            stealer = new ProcessBuilder(javaBin, "-cp", classpath,
                    "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                    "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
                    "cz.loplex.jembetter.win32check.ForegroundStealerMain")
                    .redirectErrorStream(true)
                    .start();
            System.out.println("BGFOCUS: stealer " + readLine(stealer.getInputStream()));

            boolean stolen = pollUntil(() -> !foregroundIsOurs(), 3000);
            System.out.println("BGFOCUS: (2) a third process holds the foreground => " + stolen
                    + " " + foregroundDescription());
            boolean clientLostFocus = pollUntil(() -> !CheckWindows.hasKeyboardFocus(clientHwnd), 3000);
            System.out.println("BGFOCUS: (3) the embedded client lost focus to it => " + clientLostFocus);

            // The measurement. Every other check here grants focus with this
            // process in the foreground; this one does it from the background,
            // which is the state the open question is about.
            embedHost.requestFocus();
            boolean landed = pollUntil(() -> CheckWindows.hasKeyboardFocus(clientHwnd), 4000);
            System.out.println("BGFOCUS: (4) grant landed=" + landed
                    + " (state was valid=" + (stolen && clientLostFocus) + ")"
                    + " " + foregroundDescription());

            if (!stolen || !clientLostFocus) {
                System.out.println("BGFOCUS: INCONCLUSIVE - the foreground was never taken from this process, "
                        + "so the grant above was not made from the background and says nothing about the question.");
            } else if (landed) {
                System.out.println("BGFOCUS: the grant worked from the background. One more sample against the "
                        + "gap being reachable; see this class's Javadoc before reading more into it.");
            } else {
                System.out.println("BGFOCUS: the grant did NOT land from the background. This is the state the "
                        + "open Win32Focus question predicts; a run of these is what would justify acting on it.");
            }
        } finally {
            if (stealer != null) {
                stopStealer(stealer);
            }
            endChild(child);
            host.dispose();
        }

        System.out.println("BGFOCUS: DONE - observational, no PASS/FAIL. Compare the 'grant landed=' lines "
                + "across runs rather than reading one.");
    }

    private static boolean foregroundIsOurs() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return false;
        }
        long hwnd = Pointer.nativeValue(foreground.getPointer());
        return Win32WindowFinder.pidOfWindow(hwnd) == ProcessHandle.current().pid();
    }

    private static String foregroundDescription() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return "(nothing held the foreground)";
        }
        long hwnd = Pointer.nativeValue(foreground.getPointer());
        return "(foreground pid=" + Win32WindowFinder.pidOfWindow(hwnd)
                + " " + Win32WindowFinder.describeWindow(hwnd) + ")";
    }

    private static void stopStealer(Process stealer) throws Exception {
        try {
            stealer.getOutputStream().write("STOP\n".getBytes());
            stealer.getOutputStream().flush();
        } catch (Exception alreadyGone) {
            // Nothing to stop; the forcible path below still applies.
        }
        if (!stealer.waitFor(2, TimeUnit.SECONDS)) {
            stealer.destroyForcibly();
        }
    }

    private static String readLine(InputStream in) throws Exception {
        String line = new BufferedReader(new InputStreamReader(in)).readLine();
        return line == null ? "<no output>" : line;
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
        try {
            child.getOutputStream().write('\n');
            child.getOutputStream().flush();
        } catch (Exception alreadyGone) {
            // the child's window may already be gone
        }
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }
}
