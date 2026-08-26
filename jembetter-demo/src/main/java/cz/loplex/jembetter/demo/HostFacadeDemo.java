package cz.loplex.jembetter.demo;

import cz.loplex.jembetter.host.EmbedHost;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manual demo: run this on its own — unlike {@link HostDemo}/{@link
 * ClientDemo}, it spawns {@link ClientFacadeDemo} itself as a child process
 * and embeds it via {@link EmbedHost#embed(long)}'s known-handle path, the
 * pattern this facade actually targets: a host that already knows its
 * child's pid because it spawned the process itself.
 *
 * <p>Demonstrates the same automatic resize forwarding {@code
 * EmbedSocket#open(Canvas)} always provides (unchanged under {@link
 * EmbedHost#create}) and the same crash detection {@link
 * EmbedHost#onDetached} exposes — kill the child process (including
 * {@code kill -9}) to see it fire. Unlike {@link HostDemo}, there is no
 * voluntary host-initiated detach/re-embed step here: {@link EmbedHost} is
 * a 1:1 facade for the lifetime of a single child process, so that stays
 * {@code EmbedSocket}-only — see {@link HostDemo} for it.
 */
public final class HostFacadeDemo {

    private HostFacadeDemo() {
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        JFrame frame = new JFrame("jembetter-demo facade host");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new JLabel("Host window (unrelated to the embed area)", SwingConstants.CENTER), BorderLayout.NORTH);

        Canvas placeholder = new Canvas();
        placeholder.setBackground(Color.DARK_GRAY);
        placeholder.setPreferredSize(new Dimension(400, 300));
        frame.add(placeholder, BorderLayout.CENTER);

        frame.pack();
        frame.setLocation(100, 100);
        frame.setVisible(true);

        EmbedHost host = EmbedHost.create(placeholder);
        CountDownLatch detached = new CountDownLatch(1);
        host.onDetached(() -> {
            System.out.println("Client detached (process exited or crashed).");
            detached.countDown();
        });

        System.out.println("Spawning the client as a child process...");
        Process client = spawnClient();

        System.out.println("Waiting for the client to finish announce()...");
        awaitClientReady(client);

        System.out.println("Embedding client pid " + client.pid() + "...");
        host.embed(client.pid());
        System.out.println("Client window reparented into the canvas placeholder.");

        System.out.println("Shrinking the host frame in 2s to demonstrate automatic resize forwarding...");
        Thread.sleep(2000);
        frame.setSize(frame.getWidth() - 150, frame.getHeight() - 120);
        System.out.println("Host frame resized; the embedded window should have followed the canvas automatically.");

        System.out.println("Waiting for the client to exit or be killed...");
        detached.await();
        host.close();
    }

    private static Process spawnClient() throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder processBuilder = new ProcessBuilder(javaBin,
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                ClientFacadeDemo.class.getName());
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return processBuilder.start();
    }

    /**
     * Blocks until {@link ClientFacadeDemo} prints its {@code READY} line —
     * see its Javadoc for why the host can't call {@link EmbedHost#embed}
     * before that, despite already knowing the client's pid — relaying
     * every line the client prints in the meantime (its stdout isn't
     * inherited directly, so this doubles as this demo's only way of
     * surfacing the client's own log output).
     */
    private static void awaitClientReady(Process client) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        Thread relay = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[client] " + line);
                    if (line.equals("READY")) {
                        ready.countDown();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, "client-output-relay");
        relay.setDaemon(true);
        relay.start();
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Client process never printed READY");
        }
    }
}
