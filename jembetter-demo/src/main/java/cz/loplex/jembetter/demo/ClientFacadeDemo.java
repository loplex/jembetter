package cz.loplex.jembetter.demo;

import cz.loplex.jembetter.client.EmbedClient;
import cz.loplex.jembetter.client.EmbedPlug;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Client counterpart to {@link HostFacadeDemo}, spawned by it directly as a
 * child process — not meant to be run on its own. Uses {@link
 * EmbedPlug#announce(String)} instead of {@link EmbedClient#offer}: the
 * host already knows this process's pid (it spawned it), so no Unix domain
 * socket rendezvous is needed.
 */
public final class ClientFacadeDemo {

    private ClientFacadeDemo() {
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("jembetter-demo facade client");
        frame.setUndecorated(true);
        frame.getContentPane().setBackground(Color.ORANGE);
        frame.add(new JLabel("I am the embedded client window", SwingConstants.CENTER), BorderLayout.NORTH);
        frame.setBounds(900, 100, 350, 250);
        frame.setVisible(true);

        System.out.println("Client PID: " + ProcessHandle.current().pid());

        EmbedPlug plug = EmbedPlug.create();
        plug.onHostDetached(() -> System.out.println("Host detached (process exited or crashed)."));
        plug.onEmbedded(embedderWindowId -> System.out
                .println("Embedded; embedder window id is 0x" + Long.toHexString(embedderWindowId)));
        plug.announce(null);

        // announce() must run (arming the reparent watcher and publishing
        // _XEMBED_INFO) before the host is allowed to call embed(pid) — a
        // pid alone tells the host nothing about whether announce() has run
        // yet, since resolving *a* top-level window for that pid succeeds
        // as soon as this frame is shown, well before announce() executes.
        // This one line is HostFacadeDemo's synchronization signal for
        // that; a real embedder needs some equivalent readiness signal of
        // its own when using the known-handle path against a self-spawned
        // child with no socket handshake.
        System.out.println("READY");
    }
}
