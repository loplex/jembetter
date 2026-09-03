package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.client.EmbedClientWin32;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import javax.swing.JFrame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper process for {@link SocketClientWin32Check}: a real {@link
 * EmbedClientWin32} driving a plain top-level window in its own JVM — the
 * actual client side of the advanced Win32 API, not a hand-rolled stand-in.
 *
 * <p>Prints a line-oriented event stream on stdout that the driver parses:
 *
 * <ul>
 *   <li>{@code READY pid=<n> hwnd=0x<hex>} — window up, {@link
 *       EmbedClientWin32#offer(Path)} returned (announce + connect done).</li>
 *   <li>{@code EMBEDDED parent=0x<hex>} — {@link EmbedClientWin32#onEmbedded}.</li>
 *   <li>{@code DETACHED} — {@link EmbedClientWin32#onHostDetached}.</li>
 *   <li>{@code MODALITY=true|false} — {@link EmbedClientWin32#onModalityChanged}.</li>
 *   <li>{@code RESIZED <w> <h>} — {@link EmbedClientWin32#onResized}.</li>
 *   <li>{@code FOCUS=true|false} — {@link EmbedClientWin32#onFocusChanged}.</li>
 * </ul>
 *
 * <p>Reads one command per line on stdin: {@code requestfocus} calls {@link
 * EmbedClientWin32#requestFocus()}; anything else (or EOF) ends the process.
 */
final class EmbedClientProcessMain {

    private EmbedClientProcessMain() {
    }

    public static void main(String[] args) throws Exception {
        Path hostSocket = Path.of(args[0]);

        JFrame frame = new JFrame("win32check embed-client");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 200, 150);
        frame.setVisible(true);

        // Resolve our own window handle *before* offer(): once the host
        // embeds us, the window is a reparented WS_CHILD and no longer shows
        // up in findApplicationWindowsByPid. The handle itself is stable
        // across the reparent, so capturing it here stays valid afterward.
        long pid = ProcessHandle.current().pid();
        long ownHwnd = firstOwnWindow(pid);

        EmbedClientWin32 client = new EmbedClientWin32();
        client.onEmbedded(id -> emit("EMBEDDED parent=0x" + Long.toHexString(id)));
        client.onHostDetached(() -> emit("DETACHED"));
        client.onModalityChanged(modal -> emit("MODALITY=" + modal));
        client.onResized((width, height) -> emit("RESIZED " + width + " " + height));
        client.onFocusChanged(focused -> emit("FOCUS=" + focused));

        client.offer(hostSocket);

        emit("READY pid=" + pid + " hwnd=0x" + Long.toHexString(ownHwnd));

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                if ("requestfocus".equals(line.trim())) {
                    client.requestFocus();
                } else {
                    break;
                }
            }
        } finally {
            client.close();
            frame.dispose();
        }
    }

    private static long firstOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            List<Long> found = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("this process (" + pid + ") never published a top-level window");
    }

    private static synchronized void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
