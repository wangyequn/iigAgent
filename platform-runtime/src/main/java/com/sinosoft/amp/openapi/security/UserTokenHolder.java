package com.sinosoft.amp.openapi.security;

/**
 * 用户票占位（无单点登录时恒为空）。
 * HTTP/MCP 工具在无人轨票据时不做用户级透传。
 */
public final class UserTokenHolder {

    private UserTokenHolder() {
    }

    public static void bind(String username, String token) {
        // no-op: SSO removed on this branch
    }

    public static String resolve() {
        return null;
    }

    public static String resolveOrSingleActive() {
        return null;
    }

    public static void clearThread() {
        // no-op
    }

    public static void clearAll() {
        // no-op
    }
}
