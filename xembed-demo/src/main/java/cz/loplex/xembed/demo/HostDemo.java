package cz.loplex.xembed.demo;

import cz.loplex.xembed.host.EmbedSocket;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;

/**
 * Manual demo: run this, then run {@link ClientDemo} in a second JVM on
 * the same X display. The client's window should visually jump into the
 * socket area once the handshake completes.
 */
public final class HostDemo {

    private HostDemo() {
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("xembed-demo host");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new JLabel("Host window (unrelated to the socket)", SwingConstants.CENTER), BorderLayout.CENTER);
        frame.setBounds(100, 100, 300, 150);
        frame.setVisible(true);

        EmbedSocket socket = new EmbedSocket(frame);
        socket.setBounds(450, 100, 400, 300);
        socket.open();

        System.out.println("Host PID:    " + ProcessHandle.current().pid());
        System.out.println("Socket path: " + DemoPaths.socketPath());
        System.out.println("Waiting for a client to connect...");

        socket.acceptOnce(DemoPaths.socketPath());

        System.out.println("Client window reparented into the socket at (450,100).");
    }
}
