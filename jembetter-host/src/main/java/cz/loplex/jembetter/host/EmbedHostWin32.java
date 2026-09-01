package cz.loplex.jembetter.host;

import java.awt.Canvas;
import java.nio.file.Path;

/**
 * {@link EmbedHost}'s Win32 implementation, via {@link Win32EmbedCore} — see
 * {@link EmbedHostX11} for the X11 counterpart {@link EmbedHost#create}
 * dispatches to instead off Windows, and {@link EmbedSocketWin32} for the
 * advanced-API sibling built on the same {@link Win32EmbedCore} that exposes
 * more than this narrow facade does (voluntary detach included — see {@link
 * EmbedHost}'s own Javadoc for why that's deliberately missing here).
 *
 * <p>{@code SetParent} onto the host {@code Canvas}'s own HWND, confirmed by
 * poll-verify — see {@code jembetter-core-win32}'s package-info for exactly
 * what the 2026-08-26 real-machine spike confirmed about this flow (question
 * 1).
 *
 * <p>Unlike {@link EmbedHostX11}, there is no {@code _XEMBED_INFO}/
 * {@code EMBEDDED_NOTIFY} handshake protocol to speak: Win32 has nothing
 * resembling it, so {@link #embed(long)} and {@link #embedOpaque(long)} both
 * boil down to the exact same {@code SetParent}+poll-verify operation here —
 * the design decision {@code EmbedHost}'s own Javadoc now documents.
 * Reparenting itself stays host-initiated (this class resolves the client's
 * HWND and calls {@code SetParent} itself), matching the X11 side, per the
 * spike's question 1 confirming that flow works.
 *
 * <p>Client death is detected via {@link ProcessHandle#onExit()}, confirmed
 * reliable for a foreign (not self-spawned) pid by the spike's question 3 —
 * no hand-rolled {@code Kernel32.OpenProcess}/{@code WaitForSingleObject}
 * watcher is needed. The {@link #onDetached} callback runs on whichever
 * thread completes that future (a JDK-internal thread, not the EDT).
 *
 * <p><strong>Click-to-focus:</strong> unlike {@code EmbedSocket}'s X11
 * backend (a passive {@code XGrabButton} that intercepts the press before
 * the client's own toolkit sees it, then replays it), a click on the
 * embedded area is observed rather than intercepted here — there is no
 * drop-in Win32 equivalent to X11's intercept-and-replay, since ordinary
 * window subclassing ({@code SetWindowSubclass}) only works within the
 * subclassing process's own address space and cannot reach across into a
 * genuinely separate process's HWND the way this class embeds one. Instead,
 * a single {@code Win32ClickWatcher} (a low-level mouse hook, {@code
 * SetWindowsHookEx(WH_MOUSE_LL, ...)} — runs in this process, no DLL
 * injected into the embedded one) watches every {@code WM_LBUTTONDOWN}
 * system-wide and calls {@code Win32Focus#set} whenever one lands inside
 * the currently embedded HWND's rect; see that class's Javadoc for the
 * mechanism, including what its {@code @Tag("windows")} tests under Wine
 * confirm about it versus what still needs a real-machine spike (the
 * documented caveats {@code SetWindowsHookEx} itself calls out: added
 * latency on every mouse event system-wide while installed, and UIPI
 * blocking the hook against a higher-integrity-level target). See {@code
 * docs/win32-status.md}.
 */
final class EmbedHostWin32 implements EmbedHost {

    private final Win32EmbedCore core;

    EmbedHostWin32(Canvas hostCanvas) {
        this.core = new Win32EmbedCore(hostCanvas);
    }

    @Override
    public void embed(long clientPid) {
        core.embed(clientPid);
    }

    @Override
    public void embed(Path rendezvousSocket) {
        core.embed(rendezvousSocket);
    }

    @Override
    public void embedOpaque(long clientWindowId) {
        core.embedOpaque(clientWindowId);
    }

    @Override
    public void onDetached(Runnable callback) {
        core.onDetached(callback);
    }

    @Override
    public void requestFocus() {
        core.requestFocus();
    }

    @Override
    public void close() {
        core.close();
    }

    @Override
    public void tryDestroy() {
        core.tryDestroy();
    }
}
