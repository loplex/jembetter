package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.Platform;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/**
 * Simplified 1:1 client-side facade dispatched by {@code os.name} to either
 * an X11 backend ({@link EmbedPlugX11}, over {@link EmbedClient}) or a
 * Win32 backend ({@link EmbedPlugWin32}), matching {@code
 * jembetter-host.EmbedHost} on the other side.
 *
 * <p>Reparenting is host-initiated on both backends (confirmed for Win32 by
 * a real-machine spike — see {@code jembetter-core-win32}'s package-info), so
 * this class's role is the same either way: make this process's own
 * top-level window findable/watchable and wait for the host to act. {@code
 * wmClass} disambiguation (see {@link #announce(String)}) has no Win32
 * equivalent — {@link EmbedPlugWin32} requires it to be {@code null}.
 *
 * <p><strong>Null is not a valid argument</strong> to anything here, with
 * one exception: every method rejects one with a {@link
 * NullPointerException} naming the parameter, at the call that made the
 * mistake rather than later from a background thread. The exception is
 * {@code wmClass}, where null is the ordinary value for a process that owns
 * a single top-level window.
 */
public interface EmbedPlug extends AutoCloseable {

    /** Creates a plug backed by this process's default client implementation for the current OS. */
    static EmbedPlug create() {
        return Platform.isWindows() ? new EmbedPlugWin32() : new EmbedPlugX11();
    }

    /**
     * Does everything needed to become embeddable except dial a host
     * socket, for a host that already knows this process's pid directly —
     * see {@link EmbedClient#announce(String)}. Pass {@code null} for a
     * process with a single top-level window (required on the Win32
     * backend, which has no {@code WM_CLASS} equivalent to disambiguate
     * with).
     */
    void announce(String wmClass);

    /**
     * Hands this process's pid to the host listening at {@code hostSocket}
     * so it can look this process's window up and reparent it — see {@link
     * EmbedClient#offer(Path, String)}. Pass {@code null} for {@code
     * wmClass} for a process with a single top-level window (required on
     * the Win32 backend).
     */
    void announce(Path hostSocket, String wmClass);

    /**
     * Registers a callback invoked once this window has been reparented
     * into an embedder, with the embedder's window id — see {@link
     * EmbedClient#onEmbedded}.
     */
    void onEmbedded(LongConsumer callback);

    /**
     * Registers a callback invoked when the embedding host's process exits
     * or crashes — see {@link EmbedClient#onHostDetached}.
     */
    void onHostDetached(Runnable callback);

    /**
     * Registers a callback invoked when this window gains ({@code true}) or
     * loses ({@code false}) input focus — see {@link
     * EmbedClient#onFocusChanged} for the X11 mechanism, {@link
     * EmbedPlugWin32}'s Javadoc for the Win32 one (a system-wide {@code
     * SetWinEventHook(EVENT_OBJECT_FOCUS, ...)}, since a child HWND's own
     * {@code WM_SETFOCUS}/{@code WM_KILLFOCUS} reach only the client's own
     * message loop, not cross-process).
     */
    void onFocusChanged(FocusListener callback);

    /** Stops watching for host death. */
    @Override
    void close();

    /**
     * Whether the last {@link #close()} actually stopped every background
     * thread this object owns, or gave up on one after its budget.
     * {@code true} before any close.
     *
     * <p>{@code close()} does not throw when a thread outstays its welcome,
     * and that is deliberate. Those threads are daemons, the caller cannot
     * kill one, and every native call they could still make is guarded
     * against a closed connection — so failing an application's shutdown
     * because the window system was slow for a second would manufacture a
     * problem rather than report one. This library has already made that
     * mistake once, with a hook-install budget that was set too low and
     * turned a slow install into a hard failure.
     *
     * <p>So the shape is {@link
     * java.util.concurrent.ExecutorService#awaitTermination}'s rather than
     * an exception's: teardown is best-effort and returns nothing, and
     * whether it finished is a separate question, for whoever has a reason
     * to ask it. A warning is logged either way.
     */
    default boolean closedCleanly() {
        return true;
    }

}
