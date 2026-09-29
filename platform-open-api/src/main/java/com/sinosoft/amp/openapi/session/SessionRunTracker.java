package com.sinosoft.amp.openapi.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话运行登记：sessionId → (tenant, agent, user) 路由元数据 + 是否在跑。
 * 供唤醒分发（AsyncTool 完成后的 WakeupDispatcher）定位会话归属；
 * 元数据在任意会话端点访问时登记，运行期另记 begin/end。
 */
@Component
public class SessionRunTracker {

    /** 元数据上限，超过后整体清空（防长驻进程无界增长；重新访问即会再登记）。 */
    private static final int MAX_META = 20_000;

    public record SessionMeta(String tenantId, String agentId, String userId) {
    }

    private final Map<String, SessionMeta> metas = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public void register(String sessionId, SessionMeta meta) {
        if (metas.size() >= MAX_META && !metas.containsKey(sessionId)) {
            metas.clear();
        }
        metas.put(sessionId, meta);
    }

    public SessionMeta meta(String sessionId) {
        return sessionId == null ? null : metas.get(sessionId);
    }

    public void begin(String sessionId) {
        running.add(sessionId);
    }

    public void end(String sessionId) {
        running.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return running.contains(sessionId);
    }
}
