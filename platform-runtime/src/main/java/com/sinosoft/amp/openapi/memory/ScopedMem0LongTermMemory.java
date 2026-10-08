package com.sinosoft.amp.openapi.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户 {@link LongTermMemory}：按会话作用域委托官方 {@link Mem0LongTermMemory}。
 *
 * <p>对齐 <a href="https://java.agentscope.io/v2/zh/integration/memory/mem0">AgentScope Mem0 集成</a>：
 * agentName/userId/runName 三层隔离 + 自定义 metadata。平台侧把
 * userId 映射为 {@code {tenantId}:{userId}}，agentName=agentId，runName=sessionId。
 *
 * <p>Agent 实例是进程内单例，本类按 {@link MemoryScope} 在调用时解析身份，
 * 并缓存底层 Mem0LongTermMemory（各自持有独立 OkHttpClient）。
 */
public class ScopedMem0LongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ScopedMem0LongTermMemory.class);
    private static final int MAX_CACHE = 256;

    private final Mem0Properties properties;
    private final Map<String, Mem0LongTermMemory> cache = new ConcurrentHashMap<>();

    public ScopedMem0LongTermMemory(Mem0Properties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        MemoryScope.Scope s = MemoryScope.get();
        if (s == null || msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        try {
            return resolve(s).record(msgs);
        } catch (RuntimeException e) {
            log.warn("mem0 record failed scope={}: {}", s, e.toString());
            return Mono.empty();
        }
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        MemoryScope.Scope s = MemoryScope.get();
        if (s == null || msg == null) {
            return Mono.just("");
        }
        try {
            return resolve(s).retrieve(msg).onErrorReturn("");
        } catch (RuntimeException e) {
            log.warn("mem0 retrieve failed scope={}: {}", s, e.toString());
            return Mono.just("");
        }
    }

    /** 供 Admin / 工具直接按显式身份读写。 */
    public Mono<Void> record(MemoryScope.Scope s, List<Msg> msgs) {
        if (s == null || msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return resolve(s).record(msgs);
    }

    public Mono<String> retrieve(MemoryScope.Scope s, Msg msg) {
        if (s == null || msg == null) {
            return Mono.just("");
        }
        return resolve(s).retrieve(msg).onErrorReturn("");
    }

    Mem0LongTermMemory resolve(MemoryScope.Scope s) {
        String key = s.tenantId() + "|" + s.userId() + "|" + s.agentId() + "|" + s.sessionId();
        return cache.computeIfAbsent(key, k -> {
            if (cache.size() >= MAX_CACHE) {
                cache.clear();
            }
            return build(s);
        });
    }

    private Mem0LongTermMemory build(MemoryScope.Scope s) {
        Mem0ApiType apiType = properties.apiType() == null
                || "self-hosted".equalsIgnoreCase(properties.apiType())
                ? Mem0ApiType.SELF_HOSTED
                : Mem0ApiType.PLATFORM;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", s.tenantId());
        meta.put("userId", s.userId());
        if (s.agentId() != null) {
            meta.put("agentId", s.agentId());
        }
        if (s.sessionId() != null) {
            meta.put("sessionId", s.sessionId());
        }
        return Mem0LongTermMemory.builder()
                .agentName(s.agentId() == null || s.agentId().isBlank() ? "agent" : s.agentId())
                .userId(mem0UserId(s.tenantId(), s.userId()))
                .runName(s.sessionId())
                .apiBaseUrl(properties.normalizedBaseUrl())
                .apiKey(properties.apiKey())
                .apiType(apiType)
                .timeout(Duration.ofSeconds(Math.max(3,
                        properties.timeoutSec() == null ? 60 : properties.timeoutSec())))
                .metadata(meta)
                .build();
    }

    public static String mem0UserId(String tenantId, String userId) {
        String t = tenantId == null || tenantId.isBlank() ? "acme" : tenantId.trim();
        String u = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
        // Mem0 会把 user_id 里的 ':' 规范成 '_'，这里统一用 '_' 避免过滤对不上
        return t + "_" + u;
    }

    public Mem0Properties properties() {
        return properties;
    }
}
