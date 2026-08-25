package cz.loplex.xembed.demo;

import cz.loplex.xembed.host.EmbedSocket;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.util.concurrent.CountDownLatch;

/**
 * Manual demo: run this, then run {@link ClientDemo} in a second JVM on
 * the same X display. The client's window should visually jump into the
 * canvas placeholder area and resize to fill it once the handshake
 * completes, then follow the host frame down to a smaller size a couple of
 * seconds later — via {@link EmbedSocket#open(Canvas)}'s own resize
 * tracking, not any resize code in this demo — then get voluntarily
 * released back to being a normal top-level window a couple of seconds
 * after that — run {@link ClientDemo} again afterward (or a fresh one) to
 * see the socket accept a new client in its place, without restarting this
 * host.
 *
 * <p>Kill the client process (including {@code kill -9}) instead, at any
 * point before the voluntary release, to see the crash detection fire — the
 * socket goes back to listening the same way.
 *
 * <p>The placeholder below is a real {@code Canvas} laid out with the rest
 * of this frame's UI; {@link EmbedSocket#open(Canvas)} reparents the
 * embedded window as a genuine X11 child of it, so — unlike an
 * override-redirect socket window — a heavyweight Swing popup/dialog from
 * this host now correctly renders above the embedded window instead of
 * underneath it. See it for yourself: add a {@code JPopupMenu} shown over
 * the placeholder (call {@code JPopupMenu.setDefaultLightWeightPopupEnabled(false)}
 * first to force it heavyweight) and it stacks above the embedded client,
 * exactly as confirmed against a real X server during this library's
 * initial spike.
 *
 * <p>Kill this host process (including {@code kill -9}) instead to see
 * {@link ClientDemo}'s symmetrical host-death detection fire on its side.
 */
public final class HostDemo {

    private HostDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        JFrame frame = new JFrame("xembed-demo host");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new JLabel("Host window (unrelated to the embed area)", SwingConstants.CENTER), BorderLayout.NORTH);

        Canvas placeholder = new Canvas();
        placeholder.setBackground(Color.DARK_GRAY);
        placeholder.setPreferredSize(new Dimension(400, 300));
        frame.add(placeholder, BorderLayout.CENTER);

        frame.pack();
        frame.setLocation(100, 100);
        frame.setVisible(true);

        EmbedSocket socket = new EmbedSocket(frame);
        socket.open(placeholder);
        socket.onClientDetached(() -> System.out.println(
                "Client detached (process exited or crashed). Waiting for a client to (re-)connect..."));

        CountDownLatch firstEmbed = new CountDownLatch(1);
        socket.onClientEmbedded(() -> {
            System.out.println("Client window reparented into the canvas placeholder.");
            firstEmbed.countDown();
        });

        System.out.println("Host PID:    " + ProcessHandle.current().pid());
        System.out.println("Socket path: " + DemoPaths.socketPath());
        System.out.println("Waiting for a client to connect...");

        socket.listen(DemoPaths.socketPath());
        firstEmbed.await();

        System.out.println("Shrinking the host frame in 2s to demonstrate automatic resize forwarding...");
        Thread.sleep(2000);
        frame.setSize(frame.getWidth() - 150, frame.getHeight() - 120);

        System.out.println("Host frame resized; the embedded window should have followed the canvas automatically.");

        System.out.println("Voluntarily releasing the client in 2s to demonstrate a host-initiated detach...");
        Thread.sleep(2000);
        socket.detachClient();

        System.out.println("Client released; it should now be a normal top-level window again.");
        System.out.println("Now waiting for a client to (re-)connect...");
    }
}
