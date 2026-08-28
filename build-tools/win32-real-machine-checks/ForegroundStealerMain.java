package cz.loplex.jembetter.win32check;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

import javax.swing.JFrame;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Helper process for {@link ForegroundLockCheck} / {@link FocusFallbackCheck}:
 * creates its own top-level window and immediately calls {@code
 * SetForegroundWindow} on it — a fresh process claiming foreground for its
 * own brand-new window is one of the documented exceptions to Windows'
 * foreground-lock policy — so the check's host process is reliably left
 * non-foreground to test against. Also understands one line of stdin
 * protocol: {@code ALLOW <pid>} calls {@code AllowSetForegroundWindow(pid)}
 * from this (currently foreground) process, to exercise the "called by the
 * target process" workaround. Requires the same {@code --add-opens
 * java.desktop/java.awt=ALL-UNNAMED --add-opens
 * java.desktop/sun.awt.windows=ALL-UNNAMED} flags as {@code
 * CanvasNativeHandle} for the same reason (reflecting on {@code
 * Component.peer}).
 */
final class ForegroundStealerMain {

    private ForegroundStealerMain() {
    }

    interface Extras extends com.sun.jna.Library {
        Extras INSTANCE = Native.load("user32", Extras.class);

        boolean AllowSetForegroundWindow(int dwProcessId);
    }

    public static void main(String[] args) throws Exception {
        JFrame frame = new JFrame("fg-stealer");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 200, 150);
        frame.setVisible(true);

        long hwndValue = extractHwnd(frame);
        HWND hwnd = new HWND(new Pointer(hwndValue));

        boolean setForegroundReturned = User32.INSTANCE.SetForegroundWindow(hwnd);
        long foreground = Pointer.nativeValue(User32.INSTANCE.GetForegroundWindow().getPointer());

        System.out.println("READY pid=" + ProcessHandle.current().pid()
                + " hwnd=0x" + Long.toHexString(hwndValue)
                + " setForegroundReturned=" + setForegroundReturned
                + " actuallyForeground=" + (foreground == hwndValue));
        System.out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("ALLOW ")) {
                int pid = Integer.parseInt(line.substring("ALLOW ".length()).trim());
                boolean allowed = Extras.INSTANCE.AllowSetForegroundWindow(pid);
                System.out.println("ALLOWED pid=" + pid + " result=" + allowed);
                System.out.flush();
            } else {
                break;
            }
        }
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
