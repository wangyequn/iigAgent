package com.sinosoft.amp.openapi.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期排空扫描：in-flight 归零或超时后 evict 旧版本实例。
 */
@Component
public class AgentDrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentDrainScheduler.class);

    private final AgentInstanceCache cache;

    public AgentDrainScheduler(AgentInstanceCache cache) {
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${amp.runtime.drain-sweep-ms:5000}")
    public void sweep() {
        try {
            if (!cache.snapshot().isEmpty()) {
                cache.sweepDrained();
            }
        } catch (RuntimeException e) {
            log.warn("drain sweep failed", e);
        }
    }
}
