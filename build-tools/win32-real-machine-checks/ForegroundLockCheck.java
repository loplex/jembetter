package cz.loplex.jembetter.win32check;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import cz.loplex.jembetter.core.win32.Win32Focus;

import javax.swing.JFrame;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Real-machine check: Windows' foreground-lock restriction on {@code
 * SetFocus}/{@code SetForegroundWindow} from a non-foreground process — the
 * policy the host-&gt;client focus grant ({@link Win32Focus#set}) has to work
 * around. Wine doesn't replicate it, so this can't be a {@code
 * @Tag("windows")} unit test.
 *
 * <p><b>Observational</b> — no automatic verdict. It prints, from a
 * genuinely non-foreground process: what plain {@code SetForegroundWindow}
 * does (return value vs. whether the foreground actually moved), what the
 * production {@code Win32Focus.set} does, and what {@code
 * AllowSetForegroundWindow} called by the current foreground process does.
 * {@link FocusFallbackCheck} is the gated pass/fail companion; this one is
 * here so the raw behaviour stays on the record each run.
 */
final class ForegroundLockCheck {

    private ForegroundLockCheck() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("FG-LOCK host");
        host.setUndecorated(true);
        host.setBounds(0, 0, 200, 150);
        host.setVisible(true);
        long hostHwndValue = extractHwnd(host);
        HWND hostHwnd = toHwnd(hostHwndValue);

        // Establish a known-good baseline: right after creation, this process
        // should be able to foreground its own window.
        User32.INSTANCE.SetForegroundWindow(hostHwnd);
        System.out.println("FG-LOCK: baseline foreground=" + isForeground(hostHwndValue));

        Process stealer = new ProcessBuilder(javaBin, "-cp", classpath,
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
                "cz.loplex.jembetter.win32check.ForegroundStealerMain")
                .redirectErrorStream(true)
                .start();
        long stealerPid = stealer.pid();
        String readyLine = readLine(stealer.getInputStream());
        System.out.println("FG-LOCK: stealer " + readyLine);

        boolean hostStillForeground = pollUntil(() -> !isForeground(hostHwndValue), 2000);
        System.out.println("FG-LOCK: host lost foreground after stealer started=" + hostStillForeground
                + " (currently foreground=" + isForeground(hostHwndValue) + ")");

        // Attempt 1: the production Win32Focus.set.
        Win32Focus.set(hostHwndValue);
        System.out.println("FG-LOCK: after Win32Focus.set(host) from non-foreground host - "
                + "hostIsForeground=" + isForeground(hostHwndValue));

        // Attempt 2: plain SetForegroundWindow straight at the host window.
        boolean sfwReturned = User32.INSTANCE.SetForegroundWindow(hostHwnd);
        System.out.println("FG-LOCK: after SetForegroundWindow(host) from non-foreground host - "
                + "returned=" + sfwReturned + " hostIsForeground=" + isForeground(hostHwndValue));

        // Attempt 3: the "called by the target process" workaround - ask the
        // currently-foreground stealer to grant the host permission first.
        OutputStream stealerIn = stealer.getOutputStream();
        stealerIn.write(("ALLOW " + ProcessHandle.current().pid() + "\n").getBytes());
        stealerIn.flush();
        String allowLine = readLine(stealer.getInputStream());
        System.out.println("FG-LOCK: stealer " + allowLine);

        boolean sfwReturnedAfterAllow = User32.INSTANCE.SetForegroundWindow(hostHwnd);
        System.out.println("FG-LOCK: after AllowSetForegroundWindow + SetForegroundWindow(host) - "
                + "returned=" + sfwReturnedAfterAllow + " hostIsForeground=" + isForeground(hostHwndValue));

        stealerIn.write("STOP\n".getBytes());
        stealerIn.flush();
        if (!stealer.waitFor(2, TimeUnit.SECONDS)) {
            stealer.destroyForcibly();
        }
        host.dispose();

        System.out.println("FG-LOCK: DONE - read the lines above; this check has no automatic PASS/FAIL, "
                + "see this class's Javadoc and the check's README.");
    }

    private static boolean isForeground(long hwndValue) {
        HWND fg = User32.INSTANCE.GetForegroundWindow();
        return fg != null && Pointer.nativeValue(fg.getPointer()) == hwndValue;
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

    private static String readLine(InputStream in) throws Exception {
        String line = new BufferedReader(new InputStreamReader(in)).readLine();
        return line == null ? "<no output>" : line;
    }

    private static HWND toHwnd(long value) {
        return new HWND(new Pointer(value));
    }

    private static long extractHwnd(Component component) throws ReflectiveOperationException {
        Field peerField = Component.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(component);
        Method accessor = peer.getClass().getMethod("getHWnd");
        accessor.setAccessible(true);
        return (long) accessor.invoke(peer);
    }
}
