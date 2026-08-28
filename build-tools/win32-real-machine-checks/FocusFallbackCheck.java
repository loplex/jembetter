package cz.loplex.jembetter.win32check;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

import javax.swing.JFrame;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real-machine check / regression guard for {@link
 * cz.loplex.jembetter.core.win32.Win32Focus#set}: from a genuinely
 * non-foreground process, does the production method move the foreground to
 * our own window?
 *
 * <p>This caught a real bug: {@code Win32Focus.set} trusted {@code
 * SetForegroundWindow}'s return value (which comes back {@code true} on real
 * Windows without the foreground moving) and so never ran its {@code
 * AttachThreadInput} fallback. Fixed 2026-08-28 (verify with {@code
 * GetForegroundWindow}, then run the fallback). No {@code @Tag("windows")}
 * unit test covers this — it needs a second process holding the foreground
 * and manipulates global foreground state, which doesn't belong interleaved
 * with the reactor's other GUI tests.
 *
 * <p>Runs a matrix of strategies (production {@code Win32Focus.set}; plain
 * {@code SetForegroundWindow} retry+verify; {@code AttachThreadInput} +
 * {@code BringWindowToTop}; synthetic Alt tap; {@code
 * SPI_SETFOREGROUNDLOCKTIMEOUT}=0) against a fresh steal each, verifying with
 * {@code GetForegroundWindow}. It runs the production method <b>first</b>,
 * before any other strategy can unlock the foreground lock for the rest of
 * the matrix — so a regression to the broken early-return still turns this
 * red. <b>PASS only if production {@code Win32Focus.set} works unaided</b>;
 * on FAIL the log names which alternatives did work.
 *
 * <p>Requires {@code --add-opens java.desktop/java.awt=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED} (peer reflection for
 * the host HWND) — {@code run.ps1} passes those.
 */
final class FocusFallbackCheck {

    /** {@code user32} entry points JNA's {@code User32} interface doesn't expose. */
    private interface Extras extends com.sun.jna.Library {
        Extras INSTANCE = Native.load("user32", Extras.class);

        boolean SystemParametersInfoW(int uiAction, int uiParam, Pointer pvParam, int fWinIni);
    }

    private static final int SPI_GETFOREGROUNDLOCKTIMEOUT = 0x2000;
    private static final int SPI_SETFOREGROUNDLOCKTIMEOUT = 0x2001;
    private static final int SPIF_SENDCHANGE = 0x2;

    private FocusFallbackCheck() {
    }

    @FunctionalInterface
    private interface FocusStrategy {
        void attempt(long targetHwnd) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("FOCUS host");
        host.setUndecorated(true);
        host.setBounds(0, 0, 200, 150);
        host.setVisible(true);
        long hostHwnd = extractHwnd(host);

        Map<String, FocusStrategy> strategies = new LinkedHashMap<>();
        strategies.put("production Win32Focus.set",
                t -> cz.loplex.jembetter.core.win32.Win32Focus.set(t));
        strategies.put("plain SetForegroundWindow x5 with verify",
                FocusFallbackCheck::retryPlain);
        strategies.put("AttachThreadInput + SFW/BringWindowToTop/SetFocus + verify",
                FocusFallbackCheck::attachThenForeground);
        strategies.put("synthetic Alt tap, then SetForegroundWindow",
                t -> { CheckWindows.tapAlt(); Thread.sleep(30);
                       User32.INSTANCE.SetForegroundWindow(toHwnd(t)); });
        strategies.put("SPI_SETFOREGROUNDLOCKTIMEOUT=0 (save/restore), then SFW",
                FocusFallbackCheck::withZeroLockTimeout);

        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<String, FocusStrategy> entry : strategies.entrySet()) {
            results.put(entry.getKey(), runStrategy(entry.getKey(), entry.getValue(),
                    hostHwnd, javaBin, classpath));
        }

        host.dispose();

        System.out.println();
        System.out.println("FOCUS: strategy results (foreground actually moved to the host window):");
        String firstWorking = null;
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            System.out.println("FOCUS:   [" + (entry.getValue() ? "WORKS" : "no-op") + "] " + entry.getKey());
            if (entry.getValue() && firstWorking == null) {
                firstWorking = entry.getKey();
            }
        }

        boolean productionWorks = results.getOrDefault(
                "production Win32Focus.set", false);
        boolean anyWorks = firstWorking != null;

        if (productionWorks) {
            System.out.println("FOCUS: PASS - production Win32Focus.set moved the foreground unaided");
        } else if (anyWorks) {
            System.out.println("FOCUS: FAIL - production Win32Focus.set is NOT sufficient as written; "
                    + "cheapest strategy that did work: \"" + firstWorking + "\"");
        } else {
            System.out.println("FOCUS: FAIL - NONE of the tried strategies moved the foreground; "
                    + "focus hand-off from a non-foreground process may need a different design");
        }
        System.exit(productionWorks ? 0 : 1);
    }

    private static boolean runStrategy(String name, FocusStrategy strategy, long hostHwnd,
                                       String javaBin, String classpath) throws Exception {
        Process stealer = new ProcessBuilder(javaBin, "-cp", classpath,
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
                "cz.loplex.jembetter.win32check.ForegroundStealerMain")
                .redirectErrorStream(true)
                .start();
        try {
            readLine(stealer.getInputStream()); // "READY ..."
            if (!pollUntil(() -> !isForeground(hostHwnd), 2000)) {
                System.out.println("FOCUS: [" + name + "] SKIPPED - stealer never took the foreground");
                return false;
            }

            strategy.attempt(hostHwnd);
            boolean moved = pollUntil(() -> isForeground(hostHwnd), 1500);
            System.out.println("FOCUS: [" + name + "] hostIsForeground=" + isForeground(hostHwnd));
            return moved;
        } finally {
            OutputStream in = stealer.getOutputStream();
            try {
                in.write("STOP\n".getBytes());
                in.flush();
            } catch (Exception ignored) {
                // stealer may already be gone
            }
            if (!stealer.waitFor(2, TimeUnit.SECONDS)) {
                stealer.destroyForcibly();
            }
            // Let the desktop settle before the next steal.
            Thread.sleep(200);
        }
    }

    private static void retryPlain(long targetHwnd) throws InterruptedException {
        HWND target = toHwnd(targetHwnd);
        for (int i = 0; i < 5; i++) {
            User32.INSTANCE.SetForegroundWindow(target);
            if (isForeground(targetHwnd)) {
                return;
            }
            Thread.sleep(60);
        }
    }

    private static void attachThenForeground(long targetHwnd) {
        HWND target = toHwnd(targetHwnd);
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            User32.INSTANCE.SetForegroundWindow(target);
            return;
        }
        IntByReference pid = new IntByReference();
        int foregroundTid = User32.INSTANCE.GetWindowThreadProcessId(foreground, pid);
        int ourTid = Kernel32.INSTANCE.GetCurrentThreadId();
        DWORD from = new DWORD(ourTid);
        DWORD to = new DWORD(foregroundTid);
        User32.INSTANCE.AttachThreadInput(from, to, true);
        try {
            User32.INSTANCE.SetForegroundWindow(target);
            User32.INSTANCE.BringWindowToTop(target);
            User32.INSTANCE.SetFocus(target);
        } finally {
            User32.INSTANCE.AttachThreadInput(from, to, false);
        }
    }

    private static void withZeroLockTimeout(long targetHwnd) {
        Memory buf = new Memory(4);
        Extras.INSTANCE.SystemParametersInfoW(SPI_GETFOREGROUNDLOCKTIMEOUT, 0, buf, 0);
        int previous = buf.getInt(0);
        try {
            Extras.INSTANCE.SystemParametersInfoW(SPI_SETFOREGROUNDLOCKTIMEOUT, 0,
                    Pointer.createConstant(0), SPIF_SENDCHANGE);
            User32.INSTANCE.SetForegroundWindow(toHwnd(targetHwnd));
        } finally {
            Extras.INSTANCE.SystemParametersInfoW(SPI_SETFOREGROUNDLOCKTIMEOUT, 0,
                    Pointer.createConstant(previous), SPIF_SENDCHANGE);
        }
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
