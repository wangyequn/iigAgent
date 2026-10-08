package com.sinosoft.amp.openapi.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionEventHubTest {

    private SseReplayBuffer replayBuffer;
    private SessionEventHub hub;

    @BeforeEach
    void setUp() {
        replayBuffer = new SseReplayBuffer(30_000);
        hub = new SessionEventHub(replayBuffer);
    }

    @Test
    void publishAppendsMonotonicFramesWithSessionScopedIds() {
        SseReplayBuffer.SseFrame f1 = hub.publish("s1", "AGENT_START", "{\"type\":\"AGENT_START\"}");
        SseReplayBuffer.SseFrame f2 = hub.publish("s1", "TEXT_BLOCK_DELTA", "{\"delta\":\"你\"}");
        assertEquals(1, f1.seq);
        assertEquals(2, f2.seq);
        assertEquals("s1:1", f1.id);
        assertEquals("s1:2", f2.id);

        // 另一会话独立编号
        SseReplayBuffer.SseFrame other = hub.publish("s2", "AGENT_START", "{}");
        assertEquals(1, other.seq);

        // Last-Event-ID 增量回放语义（两端共享同一缓冲）
        assertEquals(1, replayBuffer.find("s1").after(1).size());
        assertEquals(2, replayBuffer.find("s1").after(0).size());
    }

    @Test
    void subscribeRegistersAndUnsubscribes() {
        SseEmitter emitter = new SseEmitter(0L);
        hub.subscribe("s-sub", emitter, null);
        assertEquals(1, hub.subscriberCount("s-sub"));

        hub.unsubscribe("s-sub", emitter);
        assertEquals(0, hub.subscriberCount("s-sub"));
    }

    @Test
    void completeMarksBufferForResumeSemantics() {
        hub.publish("s-c", "AGENT_START", "{}");
        hub.complete("s-c");
        SseReplayBuffer.SessionBuffer buf = replayBuffer.find("s-c");
        assertNotNull(buf);
        assertEquals(true, buf.isComplete());
        assertNull(replayBuffer.find("never-published"));
    }

    @Test
    void trackerRegistersMetaAndRunningState() {
        SessionRunTracker tracker = new SessionRunTracker();
        tracker.register("t1", new SessionRunTracker.SessionMeta("acme", "demo", "u1"));
        assertEquals("demo", tracker.meta("t1").agentId());
        tracker.begin("t1");
        assertEquals(true, tracker.isRunning("t1"));
        tracker.end("t1");
        assertEquals(false, tracker.isRunning("t1"));
        assertNull(tracker.meta("unknown"));
    }
}
