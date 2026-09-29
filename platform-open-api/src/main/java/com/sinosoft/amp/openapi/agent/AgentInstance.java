package com.sinosoft.amp.openapi.agent;

import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内单例实例 + in-flight 计数，供排空（drain）判断。
 */
public class AgentInstance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentInstance.class);

    private final String cacheKey;
    private final HarnessAgent agent;
    private final long createdAt = System.currentTimeMillis();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean closed;

    public AgentInstance(String cacheKey, HarnessAgent agent) {
        this.cacheKey = cacheKey;
        this.agent = agent;
    }

    public String cacheKey() {
        return cacheKey;
    }

    public HarnessAgent agent() {
        if (closed) {
            throw new IllegalStateException("agent instance closed: " + cacheKey);
        }
        return agent;
    }

    public int enter() {
        if (closed) {
            throw new IllegalStateException("agent instance closed: " + cacheKey);
        }
        return inFlight.incrementAndGet();
    }

    public void exit() {
        inFlight.decrementAndGet();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public boolean isClosed() {
        return closed;
    }

    public long ageMs() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            agent.close();
        } catch (RuntimeException e) {
            log.warn("close HarnessAgent failed key={}", cacheKey, e);
        }
        log.info("agent instance closed key={} inFlight={}", cacheKey, inFlight.get());
    }
}
