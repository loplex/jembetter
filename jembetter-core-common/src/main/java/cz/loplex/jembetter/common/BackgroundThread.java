package cz.loplex.jembetter.common;

import org.slf4j.Logger;

import java.time.Duration;

/**
 * Waiting for a background thread to finish at teardown, with the outcome
 * both logged and answerable.
 *
 * <p>Every {@code close()} in this library signals its thread to stop and
 * then waits a bounded time for it. The bound is not negotiable: a thread
 * that will not stop — because a caller-supplied callback is blocking in it,
 * or because the window system is being slow — must not take its owner's
 * teardown with it, so the wait has to give up rather than hang.
 *
 * <p>What used to be wrong is that giving up passed in total silence. The
 * join's result was discarded at all eleven call sites, so a thread left
 * running after its owner closed was invisible from outside the process.
 * Every use-after-free this library has had was a consequence of exactly
 * that thread still running, which makes it the last thing that should have
 * been unobservable.
 */
public final class BackgroundThread {

    /**
     * How long teardown waits for a background thread before giving up on
     * it.
     *
     * <p>Deliberately not raised to the 5s the library allows the window
     * system elsewhere. What is being waited for here is not a round trip
     * but a loop noticing its stop flag, and those loops poll every 20-50ms,
     * so a second is already 20 to 50 times the expected wait. The cases
     * that overrun it — a callback that blocks indefinitely — are not fixed
     * by a longer budget either; they would only make every such shutdown
     * five times slower. These budgets also compound: a client closing three
     * watchers and a reader waits for four of them in turn.
     */
    public static final Duration STOP_BUDGET = Duration.ofSeconds(1);

    private BackgroundThread() {
    }

    /**
     * Waits up to {@link #STOP_BUDGET} for {@code thread} to finish and
     * reports whether it did, logging a warning through {@code log} if it
     * did not. Callers have already sent whatever stop signal the thread
     * listens for — a flag, an interrupt, a closed channel, a posted
     * message; this is only the waiting.
     *
     * @return {@code true} if the thread has finished, or was never started;
     *         {@code false} if it is still running
     */
    public static boolean awaitStopped(Thread thread, Logger log) {
        if (thread == null) {
            return true;
        }
        try {
            thread.join(STOP_BUDGET.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("Thread {} was still running {} after it was asked to stop; "
                    + "its owner is closed but the thread is not", thread.getName(), STOP_BUDGET);
            return false;
        }
        return true;
    }
}
