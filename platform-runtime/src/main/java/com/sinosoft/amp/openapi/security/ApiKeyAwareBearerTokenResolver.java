package com.sinosoft.amp.openapi.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import java.util.Set;

/**
 * Bearer 解析：已是平台 API Key 的 token 交给 {@link ApiKeyAuthFilter}，不进 JWT 解码。
 * 双轨并存——机器 Key 走 Key 过滤器，人 JWT 走 Resource Server。
 */
public class ApiKeyAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = (request) -> {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    };

    private final Set<String> apiKeys;

    public ApiKeyAwareBearerTokenResolver(Set<String> apiKeys) {
        this.apiKeys = apiKeys == null ? Set.of() : Set.copyOf(apiKeys);
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = delegate.resolve(request);
        if (token == null || apiKeys.contains(token)) {
            return null;
        }
        return token;
    }
}
