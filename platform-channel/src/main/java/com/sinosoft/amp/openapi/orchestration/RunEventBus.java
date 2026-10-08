package com.sinosoft.amp.openapi.orchestration;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * S11：Run 过程事件（短窗内存），供 SSE / 轮询展示。
 * 多副本可后续接 Redis pub/sub（与 ReleaseEventBus 同模式）。
 */
@Component
public class RunEventBus {

    public record RunEvent(String runId, String type, String stepId, String message, Instant at, Map<String, Object> data) {
    }

    private final Map<String, List<RunEvent>> events = new ConcurrentHashMap<>();
    private static final int MAX_PER_RUN = 200;

    public void publish(String runId, String type, String stepId, String message, Map<String, Object> data) {
        List<RunEvent> list = events.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>());
        list.add(new RunEvent(runId, type, stepId, message, Instant.now(), data == null ? Map.of() : data));
        while (list.size() > MAX_PER_RUN) {
            list.remove(0);
        }
    }

    public List<RunEvent> list(String runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    public void clear(String runId) {
        events.remove(runId);
    }
}
