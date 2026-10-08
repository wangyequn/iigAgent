package com.sinosoft.amp.openapi.memory;

/**
 * 会话级记忆作用域（租户/用户/Agent/会话）。
 *
 * <p>Agent 实例是进程内单例（key=tenant:agent:version），工具无法在构造时绑定 userId；
 * ChatController 在发起对话前 bind、结束后 clear，Mem0Toolset / 自动沉淀读取本 ThreadLocal。
 */
public final class MemoryScope {

    public record Scope(String tenantId, String userId, String agentId, String sessionId) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private MemoryScope() {
    }

    public static void bind(String tenantId, String userId, String agentId, String sessionId) {
        CURRENT.set(new Scope(tenantId, userId, agentId, sessionId));
    }

    public static Scope get() {
        return CURRENT.get();
    }

    public static Scope require() {
        Scope s = CURRENT.get();
        if (s == null) {
            throw new IllegalStateException("memory scope not bound");
        }
        return s;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
