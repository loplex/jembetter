package cz.loplex.jembetter.host;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * Test-only helper launched by {@link VisualEmbedDemoTest} as a genuinely
 * separate JVM process (see {@link FakeClientProcessMain}'s Javadoc for
 * why). Unlike that class, this one is sized and labeled to actually be
 * watched — {@code -Dtest.xserver=Xephyr} - rather than to run fast and
 * unattended.
 */
final class VisualClientProcessMain {

    private VisualClientProcessMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        JFrame frame = new JFrame("VisualEmbedDemoTest client (external process)");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 360, 260);
        frame.getContentPane().setBackground(Color.ORANGE);
        JLabel label = new JLabel("<html><center>I am the embedded<br>client window</center></html>",
                SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));
        frame.add(label, BorderLayout.CENTER);
        frame.setVisible(true);
        Thread.sleep(Long.MAX_VALUE);
    }
}
