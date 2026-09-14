package cz.loplex.jembetter.common;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackgroundThread#awaitStopped} exists because every teardown in
 * this library used to discard its join's result, so a thread still running
 * after its owner closed could not be seen from outside the process. These
 * pin down the only thing that matters about it: that it tells the truth in
 * both directions.
 */
class BackgroundThreadTest {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundThreadTest.class);

    @Test
    void reportsAThreadThatFinishes() {
        Thread thread = new Thread(() -> {
        }, "background-thread-test-finishes");
        thread.start();

        assertTrue(BackgroundThread.awaitStopped(thread, LOG), "a thread that finished was reported as still running");
    }

    @Test
    void reportsAThreadThatOutlastsItsBudget() {
        CountDownLatch release = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "background-thread-test-outlasts");
        thread.setDaemon(true);
        thread.start();

        try {
            assertFalse(BackgroundThread.awaitStopped(thread, LOG),
                    "a thread that never stopped was reported as stopped");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aThreadThatWasNeverStartedCountsAsStopped() {
        assertTrue(BackgroundThread.awaitStopped(null, LOG), "a null thread should not be reported as still running");
    }
}
