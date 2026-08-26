package cz.loplex.jembetter.demo;

import cz.loplex.jembetter.client.EmbedClient;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Manual demo counterpart to {@link HostDemo}. Undecorated so no leftover
 * window-manager decoration frame is left behind once its content window is
 * reparented away.
 *
 * <p>Exercises the client side of the XEmbed focus protocol: the "Request
 * Focus" button sends {@code XEMBED_REQUEST_FOCUS} to the embedder via
 * {@link EmbedClient#requestFocus()}, which {@code HostDemo}'s
 * {@code EmbedSocket} grants unconditionally — click it after this window
 * has been embedded and the host window (unrelated, unfocused) should lose
 * input focus to this one.
 */
public final class ClientDemo {

    private ClientDemo() {
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("jembetter-demo client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setUndecorated(true);
        frame.getContentPane().setBackground(Color.ORANGE);
        frame.add(new JLabel("I am the embedded client window", SwingConstants.CENTER), BorderLayout.NORTH);
        frame.setBounds(900, 100, 350, 250);
        frame.setVisible(true);

        System.out.println("Client PID: " + ProcessHandle.current().pid());
        System.out.println("Offering this window to the host...");

        EmbedClient client = new EmbedClient();
        client.onHostDetached(() -> System.out.println("Host detached (process exited or crashed)."));
        client.onEmbedded(embedderWindowId -> System.out
                .println("Embedded; embedder window id is 0x" + Long.toHexString(embedderWindowId)));

        JButton requestFocusButton = new JButton("Request Focus");
        requestFocusButton.addActionListener(event -> {
            System.out.println("Sending XEMBED_REQUEST_FOCUS to the embedder...");
            client.requestFocus();
        });
        frame.add(requestFocusButton, BorderLayout.CENTER);

        client.offer(DemoPaths.socketPath());

        System.out.println("Offered. If the host accepted, this window should now be reparented.");
        System.out.println("Now watching for the host to exit or crash...");
    }
}
