package cz.loplex.xembed.host;

import javax.swing.JFrame;
import java.awt.Color;

/**
 * Test-only helper launched by {@link EmbedSocketTest} as a genuinely
 * separate JVM process, so its window isn't sharing the test JVM's own AWT
 * toolkit state (leader/focus-proxy windows etc.) with the host under test.
 */
final class FakeClientProcessMain {

    private FakeClientProcessMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        JFrame frame = new JFrame("EmbedSocketTest fake client (external process)");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 30, 30);
        frame.getContentPane().setBackground(Color.ORANGE);
        frame.setVisible(true);
        Thread.sleep(Long.MAX_VALUE);
    }
}
