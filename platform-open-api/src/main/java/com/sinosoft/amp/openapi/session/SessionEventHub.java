package com.sinosoft.amp.openapi.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话事件中心：同一会话的 SSE 帧多订阅者扇出 + 短窗回放（AgentScope 2.0 会话流）。
 * 触发者（POST messages:stream）与只读订阅者（GET .../sessions/{sid}/stream）共享同一
 * {@link SseReplayBuffer} 帧编号，Last-Event-ID 语义两端一致。
 */
@Component
public class SessionEventHub {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);

    private final SseReplayBuffer replayBuffer;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SessionEventHub(SseReplayBuffer replayBuffer) {
        this.replayBuffer = replayBuffer;
    }

    /** 追加一帧到会话缓冲并扇出给所有实时订阅者；返回帧供调用方自行发送给自己的连接。 */
    public SseReplayBuffer.SseFrame publish(String sessionId, String event, String data) {
        SseReplayBuffer.SseFrame frame = replayBuffer.open(sessionId).append(event, data);
        fanout(sessionId, frame);
        return frame;
    }

    /** 只扇出（调用方已 append 到共享缓冲时使用）。 */
    public void fanout(String sessionId, SseReplayBuffer.SseFrame frame) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(sessionId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            if (!send(emitter, frame)) {
                list.remove(emitter);
            }
        }
    }

    /**
     * 只读订阅：先回放缓冲（带 Last-Event-ID 为增量，否则全量短窗），再进入实时扇出。
     * 采用「先回放后注册」避免重复投递；回放与注册之间的极小窗口可能丢帧，可通过 Last-Event-ID 重连补齐。
     */
    public void subscribe(String sessionId, SseEmitter emitter, String lastEventId) {
        long lastSeq = SseReplayBuffer.parseSeq(lastEventId, sessionId);
        SseReplayBuffer.SessionBuffer buf = replayBuffer.open(sessionId);
        if (lastEventId != null && !lastEventId.isBlank()) {
            if (lastSeq < 0) {
                sendMiss(emitter);
                emitter.complete();
                return;
            }
            for (SseReplayBuffer.SseFrame f : buf.after(lastSeq)) {
                send(emitter, f);
            }
        } else {
            for (SseReplayBuffer.SseFrame f : buf.after(0)) {
                send(emitter, f);
            }
        }
        subscribers.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> unsubscribe(sessionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onError(e -> cleanup.run());
        emitter.onTimeout(cleanup);
        log.info("session.subscribe sessionId={} subscribers={}", sessionId, subscriberCount(sessionId));
    }

    public void unsubscribe(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(sessionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribers.remove(sessionId, list);
            }
        }
    }

    /** 一轮运行结束：标记缓冲 complete（影响 POST 流的续传判定）。 */
    public void complete(String sessionId) {
        SseReplayBuffer.SessionBuffer buf = replayBuffer.find(sessionId);
        if (buf != null) {
            buf.markComplete();
        }
    }

    public int subscriberCount(String sessionId) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(sessionId);
        return list == null ? 0 : list.size();
    }

    private boolean send(SseEmitter emitter, SseReplayBuffer.SseFrame frame) {
        try {
            emitter.send(SseEmitter.event()
                    .id(frame.id)
                    .name(frame.event)
                    .data(frame.data));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void sendMiss(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("SSE_RESUME_MISS")
                    .data("{\"code\":\"SSE_RESUME_MISS\"}"));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开
        }
    }
}
