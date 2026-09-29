package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * B2：key = tenantId:agentId:version 懒加载缓存 + Endpoint 切流后排空。
 */
@Component
public class AgentInstanceCache {

    private static final Logger log = LoggerFactory.getLogger(AgentInstanceCache.class);

    private final AgentInstanceCreator factory;
    private final Map<String, AgentInstance> ready = new ConcurrentHashMap<>();
    private final Map<String, Long> drainingSince = new ConcurrentHashMap<>();
    private final int maxInstances;
    private final long drainTimeoutMs;

    public AgentInstanceCache(
            AgentInstanceCreator factory,
            @Value("${amp.runtime.max-instances:50}") int maxInstances,
            @Value("${amp.runtime.drain-timeout-ms:120000}") long drainTimeoutMs) {
        this.factory = factory;
        this.maxInstances = maxInstances;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    public static String key(String tenantId, String agentId, int version) {
        return tenantId + ":" + agentId + ":" + version;
    }

    /**
     * 懒加载获取已就绪实例；若该 key 正在排空则拒绝新流量。
     */
    public AgentInstance getOrCreate(String tenantId, String agentId, int version, String modelId, String sysPrompt, String workspace) {
        String k = key(tenantId, agentId, version);
        if (drainingSince.containsKey(k)) {
            throw new CatalogException("VERSION_DRAINING", "version is draining: " + k);
        }
        AgentInstance existing = ready.get(k);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (this) {
            existing = ready.get(k);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            ensureCapacity();
            HarnessAgent agent = factory.create(tenantId, agentId, version, modelId, sysPrompt, workspace);
            AgentInstance created = new AgentInstance(k, agent);
            ready.put(k, created);
            log.info("instance ready key={} inFlight=0", k);
            return created;
        }
    }

    /**
     * 切流：目标实例必须已 ready；旧 key 进入排空（不再接新）。
     */
    public void switchRelease(String tenantId, String agentId, int fromVersion, int toVersion) {
        String toKey = key(tenantId, agentId, toVersion);
        if (!ready.containsKey(toKey)) {
            throw new CatalogException("VERSION_NOT_READY", "target version not built: " + toKey);
        }
        if (fromVersion > 0 && fromVersion != toVersion) {
            beginDrain(tenantId, agentId, fromVersion);
        }
        log.info("endpoint switch {}:{} v{} -> v{}", tenantId, agentId, fromVersion, toVersion);
    }

    public void beginDrain(String tenantId, String agentId, int version) {
        String k = key(tenantId, agentId, version);
        if (ready.containsKey(k)) {
            drainingSince.putIfAbsent(k, System.currentTimeMillis());
            log.info("drain start key={} inFlight={}", k, ready.get(k).inFlight());
        }
    }

    /**
     * 排空：in-flight 结束或超时后 evict。返回本次 evict 的 key。
     */
    public List<String> sweepDrained() {
        List<String> evicted = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : drainingSince.entrySet()) {
            String k = entry.getKey();
            AgentInstance instance = ready.get(k);
            if (instance == null) {
                drainingSince.remove(k);
                continue;
            }
            int inFlight = instance.inFlight();
            boolean idle = inFlight <= 0;
            boolean timedOut = now - entry.getValue() >= drainTimeoutMs;
            if (idle || timedOut) {
                log.info("drain done key={} inFlight={} timedOut={} ageMs={}",
                        k, inFlight, timedOut, now - entry.getValue());
                ready.remove(k);
                drainingSince.remove(k);
                instance.close();
                evicted.add(k);
            } else {
                log.debug("drain wait key={} inFlight={}", k, inFlight);
            }
        }
        return evicted;
    }

    public boolean isDraining(String tenantId, String agentId, int version) {
        return drainingSince.containsKey(key(tenantId, agentId, version));
    }

    public int size() {
        return ready.size();
    }

    /** S5：技能审后驱逐该租户全部实例，下轮重建加载新技能。 */
    public List<String> evictTenant(String tenantId) {
        List<String> evicted = new ArrayList<>();
        String prefix = tenantId + ":";
        for (String k : List.copyOf(ready.keySet())) {
            if (k.startsWith(prefix)) {
                AgentInstance inst = ready.remove(k);
                drainingSince.remove(k);
                if (inst != null) {
                    inst.close();
                    evicted.add(k);
                }
            }
        }
        if (!evicted.isEmpty()) {
            log.info("evict tenant={} keys={}", tenantId, evicted);
        }
        return evicted;
    }

    public Map<String, Integer> snapshot() {
        Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, AgentInstance> e : ready.entrySet()) {
            map.put(e.getKey(), e.getValue().inFlight());
        }
        return map;
    }

    private void ensureCapacity() {
        // 排空完成的先清一波
        sweepDrained();
        if (ready.size() >= maxInstances) {
            throw new CatalogException("INSTANCE_CAPACITY",
                    "active agent version instance cap reached: " + maxInstances);
        }
    }

    /**
     * 请求入口包装：enter/exit 统一计数。
     */
    public Lease lease(AgentInstance instance) {
        instance.enter();
        return new Lease(instance);
    }

    public static final class Lease implements AutoCloseable {
        private final AgentInstance instance;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(AgentInstance instance) {
            this.instance = instance;
        }

        public HarnessAgent agent() {
            return instance.agent();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                instance.exit();
            }
        }
    }
}
