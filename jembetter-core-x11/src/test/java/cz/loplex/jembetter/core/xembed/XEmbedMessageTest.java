package cz.loplex.jembetter.core.xembed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XEmbedMessageTest {

    @Test
    void resolvesEveryDeclaredOpcode() {
        for (XEmbedMessage message : XEmbedMessage.values()) {
            assertEquals(message, XEmbedMessage.fromOpcode(message.opcode));
        }
    }

    @Test
    void rejectsUnknownOpcode() {
        assertThrows(IllegalArgumentException.class, () -> XEmbedMessage.fromOpcode(99));
    }
}
