package com.sinosoft.amp.openapi.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseReplayBufferTest {

    @Test
    void appendAndReplayAfterLastSeq() {
        SseReplayBuffer store = new SseReplayBuffer(30_000);
        SseReplayBuffer.SessionBuffer buf = store.open("agt_x");
        buf.append("AGENT_START", "{}");
        buf.append("TEXT_BLOCK_DELTA", "{\"delta\":\"你\"}");
        buf.append("TEXT_BLOCK_DELTA", "{\"delta\":\"好\"}");
        buf.markComplete();

        List<SseReplayBuffer.SseFrame> all = buf.after(0);
        assertEquals(3, all.size());
        assertEquals("agt_x:1", all.get(0).id);
        assertEquals("agt_x:3", all.get(2).id);

        List<SseReplayBuffer.SseFrame> rest = buf.after(1);
        assertEquals(2, rest.size());
        assertEquals(2, rest.get(0).seq);
    }

    @Test
    void parseSeqFromLastEventId() {
        assertEquals(12, SseReplayBuffer.parseSeq("agt_x:12", "agt_x"));
        assertEquals(-1, SseReplayBuffer.parseSeq("other:12", "agt_x"));
        assertEquals(-1, SseReplayBuffer.parseSeq("bad", "agt_x"));
        assertEquals(-1, SseReplayBuffer.parseSeq(null, "agt_x"));
    }

    @Test
    void expiredBufferReturnsNull() throws Exception {
        SseReplayBuffer store = new SseReplayBuffer(50);
        store.open("agt_y");
        assertNotNull(store.find("agt_y"));
        Thread.sleep(80);
        assertNull(store.find("agt_y"));
    }

    @Test
    void openReusesLiveBuffer() {
        SseReplayBuffer store = new SseReplayBuffer(30_000);
        SseReplayBuffer.SessionBuffer a = store.open("agt_z");
        a.append("AGENT_START", "{}");
        SseReplayBuffer.SessionBuffer b = store.open("agt_z");
        assertEquals(1, b.size());
        assertTrue(b.after(0).size() == 1);
    }
}
