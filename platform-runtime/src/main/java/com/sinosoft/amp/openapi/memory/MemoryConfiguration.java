package com.sinosoft.amp.openapi.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mem0 记忆层装配：官方 {@code agentscope-extensions-mem0} + 平台多租户作用域。
 */
@Configuration
public class MemoryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfiguration.class);

    @Bean
    public ScopedMem0LongTermMemory scopedMem0LongTermMemory(Mem0Properties properties) {
        Mem0Properties p = properties == null
                ? new Mem0Properties(false, null, null, null, null, null, null, null)
                : properties;
        if (p.isConfigured()) {
            log.info("mem0 long-term memory enabled type={} baseUrl={}", p.apiType(), p.normalizedBaseUrl());
        } else {
            log.info("mem0 long-term memory disabled (amp.mem0.enabled=false or baseUrl blank)");
        }
        return new ScopedMem0LongTermMemory(p);
    }

    @Bean
    public Mem0AdminClient mem0AdminClient(Mem0Properties properties) {
        Mem0Properties p = properties == null
                ? new Mem0Properties(false, null, null, null, null, null, null, null)
                : properties;
        return new Mem0AdminClient(p);
    }

    @Bean
    public Mem0MemoryMiddleware mem0MemoryMiddleware(
            ScopedMem0LongTermMemory memory,
            Mem0Properties properties,
            com.sinosoft.amp.openapi.agent.AgentProperties agentProperties) {
        Mem0Properties p = properties == null
                ? new Mem0Properties(false, null, null, null, null, null, null, null)
                : properties;
        String defaultTenant = System.getProperty("amp.default.tenant",
                System.getenv().getOrDefault("AMP_TENANT_ID", "acme"));
        return new Mem0MemoryMiddleware(memory, p, defaultTenant);
    }
}
