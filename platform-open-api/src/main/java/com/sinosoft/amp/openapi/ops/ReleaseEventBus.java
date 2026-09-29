package com.sinosoft.amp.openapi.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 多副本运行时事件：release 切流 / 租户实例驱逐。
 * 发布后各副本（含自己）收消息，本地 drain/evict，保证实例缓存一致。
 */
@Component
public class ReleaseEventBus implements InitializingBean, DisposableBean {

    public static final String CHANNEL = "amp:events:runtime";
    public static final String TYPE_RELEASE_SWITCH = "release_switch";
    public static final String TYPE_TENANT_EVICT = "tenant_evict";

    private static final Logger log = LoggerFactory.getLogger(ReleaseEventBus.class);

    private final String redisUri;
    private final boolean enabled;
    private final String replicaId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private JedisPooled publishJedis;
    private JedisPooled subJedis;
    private ExecutorService executor;
    private volatile Consumer<Map<String, Object>> handler = e -> {
    };

    public ReleaseEventBus(
            @Value("${amp.agent.redis-uri:redis://127.0.0.1:6379}") String redisUri,
            @Value("${amp.events.enabled:true}") boolean enabled) {
        this.redisUri = redisUri;
        this.enabled = enabled;
        this.replicaId = resolveReplicaId();
    }

    static String resolveReplicaId() {
        String env = System.getenv("AMP_REPLICA_ID");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getProperty("amp.replica.id", "replica-" + ProcessHandle.current().pid());
    }

    public String replicaId() {
        return replicaId;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setHandler(Consumer<Map<String, Object>> handler) {
        this.handler = handler == null ? e -> {
        } : handler;
    }

    public void publishReleaseSwitch(String tenantId, String agentId, int fromVersion, int toVersion) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", TYPE_RELEASE_SWITCH);
        e.put("tenantId", tenantId);
        e.put("agentId", agentId);
        e.put("fromVersion", fromVersion);
        e.put("toVersion", toVersion);
        publish(e);
    }

    public void publishTenantEvict(String tenantId, String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", TYPE_TENANT_EVICT);
        e.put("tenantId", tenantId);
        e.put("reason", reason);
        publish(e);
    }

    void publish(Map<String, Object> event) {
        if (!enabled) {
            return;
        }
        event.put("origin", replicaId);
        event.put("ts", System.currentTimeMillis());
        try {
            if (publishJedis == null) {
                publishJedis = new JedisPooled(redisUri);
            }
            publishJedis.publish(CHANNEL, mapper.writeValueAsString(event));
            log.debug("event publish type={} tenant={}", event.get("type"), event.get("tenantId"));
        } catch (Exception ex) {
            log.warn("event publish failed type={} err={}", event.get("type"), ex.toString());
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("release event bus disabled");
            return;
        }
        running.set(true);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "amp-release-events");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::listenLoop);
        log.info("release event bus started replica={} channel={}", replicaId, CHANNEL);
    }

    private void listenLoop() {
        while (running.get()) {
            try {
                if (subJedis == null) {
                    subJedis = new JedisPooled(redisUri);
                }
                JedisPubSub sub = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        handleMessage(message);
                    }
                };
                subJedis.subscribe(sub, CHANNEL);
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("event subscribe interrupted, retry in 3s: {}", e.toString());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    void handleMessage(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = mapper.readValue(message, Map.class);
            Object origin = event.get("origin");
            if (replicaId.equals(String.valueOf(origin))) {
                // 本副本已在 publish 路径处理过本地状态
                return;
            }
            handler.accept(event);
            log.info("event handled type={} origin={} tenant={}",
                    event.get("type"), origin, event.get("tenantId"));
        } catch (Exception e) {
            log.warn("event handle failed msg={} err={}", message, e.toString());
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        closeQuietly(subJedis);
        closeQuietly(publishJedis);
    }

    private static void closeQuietly(JedisPooled j) {
        if (j != null) {
            try {
                j.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
