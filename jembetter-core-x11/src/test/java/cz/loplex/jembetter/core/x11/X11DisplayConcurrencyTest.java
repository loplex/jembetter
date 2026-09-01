package cz.loplex.jembetter.core.x11;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link X11Display#GLOBAL_LOCK}: hammers several
 * independent {@link X11Display} connections with concurrent Xlib calls
 * while a background watcher drives its own, separate connection at the
 * same time — the exact combination that used to corrupt {@code
 * XErrorEvent}s (implausible {@code resourceid}s, non-standard error codes)
 * before every native call in this library was made to synchronize on one
 * process-wide lock instead of relying on {@code XInitThreads}, see the
 * class Javadoc on {@link X11Display}.
 *
 * <p>A race like this can't be reproduced deterministically on every run,
 * so this is a stress/smoke test rather than a strict repro: it asserts
 * every worker thread finishes cleanly and that any X11 error captured
 * during the run references a window this test actually created, i.e.
 * hasn't been corrupted into referencing an implausible resourceid.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class X11DisplayConcurrencyTest {

    @Test
    void concurrentXlibCallsAcrossMultipleConnectionsProduceNoCorruptedErrors() throws InterruptedException {
        X11ErrorHandler.install();

        int threadCount = 8;
        int iterationsPerThread = 100;

        List<Long> createdWindowIds = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));

        AtomicBoolean watcherRunning = new AtomicBoolean(true);
        Thread watcher = new Thread(() -> {
            try (X11Display watcherDisplay = X11Display.open(null)) {
                long root = watcherDisplay.defaultRootWindow().longValue();
                while (watcherRunning.get()) {
                    WindowGeometry.rootPosition(watcherDisplay, root);
                }
            }
        }, "x11-concurrency-test-watcher");
        watcher.setDaemon(true);
        watcher.start();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try (X11Display display = X11Display.open(null)) {
                        for (int i = 0; i < iterationsPerThread; i++) {
                            long windowId = RawWindow.createOverrideRedirect(display, 0, 0, 10, 10);
                            createdWindowIds.add(windowId);
                            WindowGeometry.moveResize(display, windowId, i % 50, i % 50, 20, 20);
                            WindowGeometry.raise(display, windowId);
                            WindowGeometry.rootPosition(display, windowId);
                            RawWindow.destroy(display, windowId);
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(60, TimeUnit.SECONDS), "worker threads never finished");
        } finally {
            watcherRunning.set(false);
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            System.setErr(originalErr);
        }

        assertTrue(failures.isEmpty(), "worker thread(s) threw: " + failures);

        String logged = captured.toString(StandardCharsets.UTF_8);
        Pattern resourceIdPattern = Pattern.compile("resourceid=(\\d+)");
        Matcher matcher = resourceIdPattern.matcher(logged);
        while (matcher.find()) {
            long resourceId = Long.parseLong(matcher.group(1));
            assertTrue(resourceId == 0 || createdWindowIds.contains(resourceId),
                    "X11 error referenced a resourceid this test never created (possible corrupted XErrorEvent): "
                            + resourceId + " in: " + logged);
        }
    }
}
