package cz.loplex.xembed.demo;

import cz.loplex.xembed.host.EmbedSocket;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.util.concurrent.CountDownLatch;

/**
 * Manual demo: run this, then run {@link ClientDemo} in a second JVM on
 * the same X display. The client's window should visually jump into the
 * socket area and resize to fill it once the handshake completes, then
 * follow the socket down to a smaller size a couple of seconds later. Kill
 * the client process (including {@code kill -9}) afterwards to see the
 * crash detection fire — the socket goes back to listening automatically, so
 * running {@link ClientDemo} again re-embeds it without restarting this host.
 *
 * <p>The socket area is now a raw, override-redirect X11 window rather than
 * an AWT one (see {@link EmbedSocket}'s Javadoc), so it's positioned with an
 * explicit {@code open(x, y, width, height)}/{@code setBounds(...)} call
 * instead of AWT layout — a real host would drive those calls from a
 * placeholder Swing component's own resize/move listener.
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
        frame.add(new JLabel("Host window (unrelated to the socket)", SwingConstants.CENTER), BorderLayout.CENTER);
        frame.setBounds(100, 100, 300, 150);
        frame.setVisible(true);

        EmbedSocket socket = new EmbedSocket(frame);
        socket.open(450, 100, 400, 300);
        socket.onClientDetached(() -> System.out.println(
                "Client detached (process exited or crashed). Waiting for a client to (re-)connect..."));

        CountDownLatch firstEmbed = new CountDownLatch(1);
        socket.onClientEmbedded(() -> {
            System.out.println("Client window reparented and resized to fill the socket at (450,100).");
            firstEmbed.countDown();
        });

        System.out.println("Host PID:    " + ProcessHandle.current().pid());
        System.out.println("Socket path: " + DemoPaths.socketPath());
        System.out.println("Waiting for a client to connect...");

        socket.listen(DemoPaths.socketPath());
        firstEmbed.await();

        System.out.println("Shrinking the socket in 2s to demonstrate live resize forwarding...");
        Thread.sleep(2000);
        socket.setBounds(450, 100, 250, 180);

        System.out.println("Socket resized to 250x180; the embedded window should have followed.");
        System.out.println("Now watching for the client to exit or crash, and accepting reconnects...");
    }
}
