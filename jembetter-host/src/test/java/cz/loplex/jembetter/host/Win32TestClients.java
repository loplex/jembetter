package cz.loplex.jembetter.host;

import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
}
