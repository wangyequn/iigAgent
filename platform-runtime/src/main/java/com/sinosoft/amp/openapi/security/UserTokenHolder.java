package com.sinosoft.amp.openapi.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人轨用户票跨线程解析。
 *
 * <p>JeecgAuthFilter 在请求线程写入；工具/MCP 可能在 Reactor 工作线程执行，
 * 普通 ThreadLocal 会丢票。这里提供：线程票（含 Inheritable）+ 按用户名登记的活跃票。
 */
public final class UserTokenHolder {

    private static final InheritableThreadLocal<String> THREAD = new InheritableThreadLocal<>();
    private static final Map<String, String> BY_USER = new ConcurrentHashMap<>();
    private static final int MAX_USERS = 256;

    private UserTokenHolder() {
    }

    public static void bind(String username, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        THREAD.set(token.trim());
        if (username != null && !username.isBlank()) {
            BY_USER.put(username.trim(), token.trim());
            while (BY_USER.size() > MAX_USERS) {
                String k = BY_USER.keySet().iterator().next();
                BY_USER.remove(k);
            }
        }
    }

    public static String resolve() {
        String t = THREAD.get();
        if (t != null && !t.isBlank()) {
            return t;
        }
        t = AuthContext.currentUserToken();
        if (t != null && !t.isBlank()) {
            return t;
        }
        JeecgIdentity jeecg = AuthContext.currentJeecg();
        if (jeecg != null && jeecg.username() != null) {
            return BY_USER.get(jeecg.username());
        }
        // 仅当当前是人轨身份上下文时，避免误用他人票
        return null;
    }

    /** 兜底：活跃登录仅一人时可用于工具（本地联调）。 */
    public static String resolveOrSingleActive() {
        String t = resolve();
        if (t != null) {
            return t;
        }
        return BY_USER.size() == 1 ? BY_USER.values().iterator().next() : null;
    }

    public static void clearThread() {
        THREAD.remove();
    }

    public static void clearAll() {
        THREAD.remove();
        BY_USER.clear();
    }
}
