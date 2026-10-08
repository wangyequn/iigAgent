package com.sinosoft.amp.openapi.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 短窗重放缓冲（设计 §6.1）：默认内存、TTL 30s。
 * 事件 id = {sessionId}:{seq}；Last-Event-ID 断线续传。
 */
@Component
public class SseReplayBuffer {

    private final long ttlMs;
    private final Map<String, SessionBuffer> buffers = new ConcurrentHashMap<>();

    public SseReplayBuffer(@Value("${amp.sse.replay-ttl-ms:30000}") long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public SessionBuffer open(String sessionId) {
        sweep();
        return buffers.compute(sessionId, (k, old) -> {
            SessionBuffer buf = old != null && !old.expired(ttlMs) ? old : new SessionBuffer(sessionId);
            buf.touch();
            return buf;
        });
    }

    public SessionBuffer find(String sessionId) {
        sweep();
        SessionBuffer buf = buffers.get(sessionId);
        if (buf == null || buf.expired(ttlMs)) {
            return null;
        }
        return buf;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        buffers.entrySet().removeIf(e -> e.getValue().expired(ttlMs));
    }

    public static final class SseFrame {
        public final long seq;
        public final String id;
        public final String event;
        public final String data;

        public SseFrame(long seq, String id, String event, String data) {
            this.seq = seq;
            this.id = id;
            this.event = event;
            this.data = data;
        }
    }

    public static final class SessionBuffer {
        private final String sessionId;
        private final List<SseFrame> frames = new ArrayList<>();
        private volatile boolean complete;
        private volatile long updatedAt = System.currentTimeMillis();
        private long seq;

        SessionBuffer(String sessionId) {
            this.sessionId = sessionId;
        }

        public synchronized SseFrame append(String event, String data) {
            long s = ++seq;
            String id = sessionId + ":" + s;
            SseFrame frame = new SseFrame(s, id, event, data);
            frames.add(frame);
            touch();
            return frame;
        }

        public synchronized List<SseFrame> after(long lastSeq) {
            touch();
            List<SseFrame> out = new ArrayList<>();
            for (SseFrame f : frames) {
                if (f.seq > lastSeq) {
                    out.add(f);
                }
            }
            return out;
        }

        public synchronized int size() {
            return frames.size();
        }

        public void markComplete() {
            complete = true;
            touch();
        }

        public boolean isComplete() {
            return complete;
        }

        void touch() {
            updatedAt = System.currentTimeMillis();
        }

        boolean expired(long ttlMs) {
            return System.currentTimeMillis() - updatedAt > ttlMs;
        }

        public String sessionId() {
            return sessionId;
        }
    }

    /** 解析 Last-Event-ID = {sessionId}:{seq} → seq；无法解析返回 -1。 */
    public static long parseSeq(String lastEventId, String sessionId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1;
        }
        String id = lastEventId.trim();
        int idx = id.lastIndexOf(':');
        if (idx < 0) {
            return -1;
        }
        String sid = id.substring(0, idx);
        if (sessionId != null && !sessionId.isBlank() && !sid.equals(sessionId)) {
            return -1;
        }
        try {
            return Long.parseLong(id.substring(idx + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
