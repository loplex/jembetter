package cz.loplex.jembetter.host;

import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spawns and locates {@link FakeClientProcessMain} instances for the Win32
 * {@code @Tag("windows")} tests — shared by {@link EmbedHostWin32Test} and
 * {@link EmbedSocketWin32Test} rather than duplicated between them.
 */
final class Win32TestClients {

    private Win32TestClients() {
    }

    static Process startFakeClientProcess() throws IOException {
        String javaBin = System.getProperty("java.home") + "\\bin\\java.exe";
        ProcessBuilder processBuilder = new ProcessBuilder(javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                FakeClientProcessMain.class.getName());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }

    static long waitForOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            List<Long> found = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fake client window never became visible; top-level windows for pid " + pid
                + ": " + Win32WindowFinder.findTopLevelWindowsByPid(pid).stream()
                        .map(Win32WindowFinder::describeWindow)
                        .collect(java.util.stream.Collectors.joining("; ")));
    }

    /**
     * Connects to a rendezvous socket once its binder's background thread
     * has bound it, retrying until then. Polling the connect rather than
     * {@link java.nio.file.Files#exists} on the socket path is deliberate:
     * the Wine-hosted JDK these tests run under implements Windows {@code
     * AF_UNIX} without ever materialising a filesystem socket node, so
     * {@code Files.exists} on the bound path stays {@code false} forever
     * even though the socket is fully connectable.
     */
    static SocketChannel connectWhenReady(Path socketPath, AtomicReference<Throwable> binderFailure)
            throws InterruptedException, IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Throwable failure = binderFailure.get();
            if (failure != null) {
                throw new IllegalStateException("Rendezvous socket binder threw before binding it", failure);
            }
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(address);
                return channel;
            } catch (IOException notReadyYet) {
                channel.close();
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Rendezvous socket was never bound", notReadyYet);
                }
                Thread.sleep(20);
            }
        }
    }
}
