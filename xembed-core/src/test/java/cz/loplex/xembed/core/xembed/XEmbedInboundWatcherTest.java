package cz.loplex.xembed.core.xembed;

import com.sun.jna.platform.unix.X11.Display;
import cz.loplex.xembed.core.x11.RawWindow;
import cz.loplex.xembed.core.x11.X11Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class XEmbedInboundWatcherTest {

    private X11Display ownerDisplay;
    private long embedderWindowId;
    private XEmbedInboundWatcher watcher;

    @BeforeEach
    void createEmbedderWindow() {
        ownerDisplay = X11Display.open(null);
        embedderWindowId = RawWindow.createOverrideRedirect(ownerDisplay, 0, 0, 10, 10);
        watcher = new XEmbedInboundWatcher(ownerDisplay, embedderWindowId);
    }

    @AfterEach
    void tearDown() {
        watcher.close();
        RawWindow.destroy(ownerDisplay, embedderWindowId);
        ownerDisplay.close();
    }

    @Test
    void receivesAClientMessageSentByADifferentConnection() throws InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<XEmbedMessage> reportedMessage = new AtomicReference<>();
        AtomicLong reportedDetail = new AtomicLong(-1);
        watcher.onClientMessage((message, detail) -> {
            reportedMessage.set(message);
            reportedDetail.set(detail);
            received.countDown();
        });

        // A separate connection stands in for the embedded client process,
        // which never shares a connection with the embedder.
        try (X11Display clientDisplay = X11Display.open(null)) {
            XEmbedMessages.send(clientDisplay.raw(), embedderWindowId, XEmbedMessage.REQUEST_FOCUS, 7, 0, 0);
        }

        assertTrue(received.await(5, TimeUnit.SECONDS), "ClientMessage was never delivered to the watcher");
        assertEquals(XEmbedMessage.REQUEST_FOCUS, reportedMessage.get());
        assertEquals(7L, reportedDetail.get());
    }

    @Test
    void receivesPropertyNotifyOnAWatchedClientsXEmbedInfo() throws InterruptedException {
        long clientWindowId = RawWindow.createOverrideRedirect(ownerDisplay, 0, 0, 10, 10);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicLong reportedWindowId = new AtomicLong(-1);
            watcher.onEmbeddedInfoChanged(id -> {
                reportedWindowId.set(id);
                received.countDown();
            });

            watcher.watchEmbeddedInfo(clientWindowId);

            Display raw = ownerDisplay.raw();
            XEmbedInfoProperty.write(raw, clientWindowId, new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, 0));

            assertTrue(received.await(5, TimeUnit.SECONDS), "PropertyNotify was never delivered to the watcher");
            assertEquals(clientWindowId, reportedWindowId.get());
        } finally {
            RawWindow.destroy(ownerDisplay, clientWindowId);
        }
    }
}
