package cz.loplex.jembetter.win32check;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.host.EmbedSocketWin32;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Real-machine check pairing {@code jembetter-host}'s {@link EmbedSocketWin32}
 * with {@code jembetter-client}'s {@code EmbedClientWin32} — the advanced
 * Win32 API's two halves run <em>against each other</em>, cross-process, on a
 * genuine {@code windows-latest} / DWM, for the first time. Their reactor
 * unit suites ({@code EmbedSocketWin32Test}, {@code EmbedClientWin32Test})
 * only ever exercise each against a hand-rolled stand-in for the other, since
 * the two modules don't depend on one another, and only under Wine.
 *
 * <p>Six gated transitions against a real {@link EmbedClientProcessMain}
 * child JVM embedded via {@link EmbedSocketWin32#listen}:
 *
 * <ol>
 *   <li><b>embed:</b> the client's {@code onEmbedded} fires with the host
 *       canvas HWND as the embedder.</li>
 *   <li><b>modality both ways:</b> {@link EmbedSocketWin32#setModal(boolean)}
 *       {@code true} then {@code false} each reach the client's {@code
 *       onModalityChanged} over the kept-open control channel.</li>
 *   <li><b>client-requested focus:</b> the client's {@code requestFocus()}
 *       marker byte makes the host focus the embedded window — verified with
 *       an independent {@code GetGUIThreadInfo} probe (see {@link
 *       CheckWindows#hasKeyboardFocus}).</li>
 *   <li><b>resize propagation:</b> growing the host canvas drives the
 *       embedded child's own {@code Win32ConfigureWatcher} to report the new
 *       {@code GetClientRect} size via {@code onResized}.</li>
 *   <li><b>voluntary host detach:</b> {@link EmbedSocketWin32#detachClient()}
 *       makes the client observe {@code onHostDetached}.</li>
 *   <li><b>re-embed:</b> a second client connects and embeds on the same
 *       still-listening socket after the first detached.</li>
 * </ol>
 *
 * <p>Requires {@code --add-opens java.desktop/java.awt=ALL-UNNAMED
 * --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED} (host canvas HWND
 * extraction, see {@code CanvasNativeHandle}) — {@code run.ps1} passes those.
 */
final class SocketClientWin32Check {

    private static final List<String> childLines = new CopyOnWriteArrayList<>();

    private SocketClientWin32Check() {
    }

    public static void main(String[] args) throws Exception {
        String javaBin = args.length > 0 ? args[0] : "java";
        String classpath = args.length > 1 ? args[1] : System.getProperty("java.class.path");

        JFrame host = new JFrame("SOCKETCLIENT host");
        Canvas canvas = new Canvas();
        canvas.setSize(320, 240);
        host.add(canvas, BorderLayout.CENTER);
        host.pack();
        host.setVisible(true);
        Thread.sleep(300);
        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        System.out.println("SOCKETCLIENT: host canvas HWND=0x" + Long.toHexString(canvasHwnd));

        Path socketPath = Files.createTempFile("jembetter-win32check-socketclient-", ".sock");
        Files.delete(socketPath);

        boolean qEmbed;
        boolean qModalOn;
        boolean qModalOff;
        boolean qFocus;
        boolean qResize;
        boolean qDetach;
        boolean qReembed;

        try (EmbedSocketWin32 socket = new EmbedSocketWin32(canvas)) {
            socket.listen(socketPath);

            Process child = startClient(javaBin, classpath, socketPath);
            long childPid = awaitReadyPid(child, 15000);
            long childHwnd = readyHwnd();
            System.out.println("SOCKETCLIENT: child pid=" + childPid
                    + " hwnd=0x" + Long.toHexString(childHwnd));

            // (1) embed
            String embeddedLine = awaitLine("EMBEDDED parent=0x", 0, 8000);
            long reportedParent = embeddedLine == null ? -1
                    : Long.parseLong(embeddedLine.substring("EMBEDDED parent=0x".length()).trim(), 16);
            qEmbed = reportedParent == canvasHwnd;
            System.out.println("SOCKETCLIENT: (1) embed - client reported parent=0x"
                    + Long.toHexString(reportedParent) + " (want 0x" + Long.toHexString(canvasHwnd)
                    + ") => " + qEmbed);

            // (2) modality, both directions
            socket.setModal(true);
            qModalOn = awaitLine("MODALITY=true", 0, 5000) != null;
            socket.setModal(false);
            qModalOff = awaitLine("MODALITY=false", 0, 5000) != null;
            System.out.println("SOCKETCLIENT: (2) modality - on=" + qModalOn + " off=" + qModalOff);

            // (3) client-initiated focus request -> host grants it
            send(child, "requestfocus");
            qFocus = pollUntil(() -> CheckWindows.hasKeyboardFocus(childHwnd), 5000);
            System.out.println("SOCKETCLIENT: (3) requestFocus() - GetGUIThreadInfo says the embedded window "
                    + (qFocus ? "DOES" : "does NOT") + " hold keyboard focus after the marker byte");

            // (4) resize propagation - grow the host canvas; the client's own
            // Win32ConfigureWatcher must see its new GetClientRect size.
            int markerBefore = childLines.size();
            SwingUtilities.invokeAndWait(() -> {
                host.setSize(560, 420);
                host.validate();
            });
            String resizedLine = awaitLine("RESIZED ", markerBefore, 5000);
            qResize = resizedLine != null && parseResizedWidth(resizedLine) >= 400;
            System.out.println("SOCKETCLIENT: (4) resize - client reported '" + resizedLine + "' => " + qResize);

            // (5) voluntary host detach
            socket.detachClient();
            qDetach = awaitLine("DETACHED", markerBefore, 5000) != null;
            System.out.println("SOCKETCLIENT: (5) detachClient() - client observed onHostDetached => " + qDetach);
            endChild(child);

            // (6) re-embed a second client on the same still-listening socket
            childLines.clear();
            Process second = startClient(javaBin, classpath, socketPath);
            awaitReadyPid(second, 15000);
            qReembed = awaitLine("EMBEDDED parent=0x", 0, 8000) != null;
            System.out.println("SOCKETCLIENT: (6) re-embed - a second client embedded on the same socket => "
                    + qReembed);
            endChild(second);
        } finally {
            host.dispose();
            Files.deleteIfExists(socketPath);
        }

        boolean passed = qEmbed && qModalOn && qModalOff && qFocus && qResize && qDetach && qReembed;
        System.out.println("SOCKETCLIENT: " + (passed ? "PASS" : "FAIL")
                + " (embed=" + qEmbed + ", modalOn=" + qModalOn + ", modalOff=" + qModalOff
                + ", focus=" + qFocus + ", resize=" + qResize + ", detach=" + qDetach
                + ", reembed=" + qReembed + ")");
        System.exit(passed ? 0 : 1);
    }

    private static Process startClient(String javaBin, String classpath, Path socketPath) throws IOException {
        Process child = new ProcessBuilder(javaBin,
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.windows=ALL-UNNAMED",
                "-cp", classpath,
                "cz.loplex.jembetter.win32check.EmbedClientProcessMain", socketPath.toString())
                .redirectErrorStream(true)
                .start();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    childLines.add(line);
                    System.out.println("  [client] " + line);
                }
            } catch (IOException ignored) {
                // child gone - nothing more to read
            }
        }, "win32check-client-stdout-reader");
        reader.setDaemon(true);
        reader.start();
        return child;
    }

    private static long awaitReadyPid(Process child, long timeoutMillis) throws InterruptedException {
        String line = awaitLine("READY pid=", 0, timeoutMillis);
        if (line == null) {
            child.destroyForcibly();
            throw new IllegalStateException("client never printed READY");
        }
        String pidToken = line.substring("READY pid=".length()).split("\\s+")[0];
        return Long.parseLong(pidToken.trim());
    }

    private static long readyHwnd() {
        for (String line : childLines) {
            int at = line.indexOf("hwnd=0x");
            if (line.startsWith("READY ") && at >= 0) {
                return Long.parseLong(line.substring(at + "hwnd=0x".length()).trim(), 16);
            }
        }
        throw new IllegalStateException("no READY line with an hwnd= token seen");
    }

    private static int parseResizedWidth(String resizedLine) {
        String[] parts = resizedLine.trim().split("\\s+");
        return Integer.parseInt(parts[1]);
    }

    /** First line at index >= {@code fromIndex} starting with {@code prefix}, or null within the timeout. */
    private static String awaitLine(String prefix, int fromIndex, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            for (int i = Math.max(0, fromIndex); i < childLines.size(); i++) {
                String line = childLines.get(i);
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return null;
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

    private static void send(Process child, String command) throws IOException {
        child.getOutputStream().write((command + "\n").getBytes());
        child.getOutputStream().flush();
    }

    private static void endChild(Process child) throws InterruptedException {
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
            // child's stdin may already be gone
        }
        if (!child.waitFor(3, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }
}
