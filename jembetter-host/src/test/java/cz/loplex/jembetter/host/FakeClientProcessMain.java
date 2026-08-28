package cz.loplex.jembetter.host;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.MSG;

import javax.swing.JFrame;
import java.awt.Color;

/**
 * Test-only helper launched by {@link EmbedSocketTest} (and {@link
 * EmbedHostWin32Test}) as a genuinely separate JVM process, so its window
 * isn't sharing the test JVM's own AWT toolkit state (leader/focus-proxy
 * windows etc.) with the host under test.
 *
 * <p>On X11 the client window is a plain undecorated {@link JFrame}: the X11
 * host resolves it via {@code _NET_WM_PID}/{@code WM_CLASS}, both of which an
 * AWT frame publishes.
 *
 * <p>On Windows the client window is instead a raw {@code CreateWindowEx}
 * top-level window with its own message loop, <em>not</em> a Swing frame:
 * under the Wine-hosted JDK used to run this repo's {@code @Tag("windows")}
 * tests on Linux, a cross-process {@code EnumWindows} never sees another
 * process's AWT frame
 * (only its message-only helper windows), so an AWT-based fake client can't
 * be found by the host at all. A raw top-level window is enumerable across
 * processes there, matching how the library's real clients (native
 * top-level windows) behave.
 */
final class FakeClientProcessMain {

    private FakeClientProcessMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            runWin32Client();
        } else {
            runX11Client();
        }
    }

    private static void runX11Client() throws InterruptedException {
        JFrame frame = new JFrame("EmbedSocketTest fake client (external process)");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 30, 30);
        frame.getContentPane().setBackground(Color.ORANGE);
        frame.setVisible(true);
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void runWin32Client() {
        HWND hwnd = User32.INSTANCE.CreateWindowEx(0, "STATIC",
                "EmbedSocketTest fake client (external process)",
                WinUser.WS_POPUP | WinUser.WS_VISIBLE,
                0, 0, 30, 30, null, null, null, null);
        if (hwnd == null) {
            throw new IllegalStateException("CreateWindowEx failed for the fake Win32 client window");
        }
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOWNORMAL);
        User32.INSTANCE.UpdateWindow(hwnd);

        MSG msg = new MSG();
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }
    }
}
