package cz.loplex.jembetter.win32check;

import javax.swing.JFrame;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Helper process for {@link ReparentWatcherCheck}: a plain top-level window
 * in its own JVM, standing in for a real separate-process client window.
 * Prints its own pid once the window is up, then blocks on stdin so the
 * parent process controls its lifetime (closing/writing to the child's
 * stdin ends it).
 */
final class ChildWindowMain {

    private ChildWindowMain() {
    }

    public static void main(String[] args) throws Exception {
        String title = args.length > 0 ? args[0] : "win32check-child-window";
        JFrame frame = new JFrame(title);
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 200, 150);
        frame.setVisible(true);

        System.out.println("READY pid=" + ProcessHandle.current().pid());
        System.out.flush();

        new BufferedReader(new InputStreamReader(System.in)).readLine();
    }
}
