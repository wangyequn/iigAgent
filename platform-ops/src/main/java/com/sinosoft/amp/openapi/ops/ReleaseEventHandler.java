package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 订阅 ReleaseEventBus：远端切流 → 本地 drain 旧版；远端技能审/驱逐 → 本地 evict。
 */
@Component
public class ReleaseEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReleaseEventHandler.class);

    private final ReleaseEventBus eventBus;
    private final AgentInstanceCache instances;
    private final CatalogRuntimeService catalogRuntime;

    public ReleaseEventHandler(
            ReleaseEventBus eventBus,
            AgentInstanceCache instances,
            CatalogRuntimeService catalogRuntime) {
        this.eventBus = eventBus;
        this.instances = instances;
        this.catalogRuntime = catalogRuntime;
    }

    @PostConstruct
    void wire() {
        eventBus.setHandler(this::onEvent);
    }

    void onEvent(Map<String, Object> event) {
        if (event == null) {
            return;
        }
        String type = String.valueOf(event.get("type"));
        if (ReleaseEventBus.TYPE_RELEASE_SWITCH.equals(type)) {
            String tenantId = str(event.get("tenantId"));
            String agentId = str(event.get("agentId"));
            int from = intOf(event.get("fromVersion"));
            if (from > 0) {
                instances.beginDrain(tenantId, agentId, from);
            }
            // 目标版本懒加载；此处只保证旧版排空
            log.info("remote release switch applied tenant={} agent={} from={}", tenantId, agentId, from);
        } else if (ReleaseEventBus.TYPE_TENANT_EVICT.equals(type)) {
            String tenantId = str(event.get("tenantId"));
            instances.evictTenant(tenantId);
            log.info("remote tenant evict applied tenant={} reason={}", tenantId, event.get("reason"));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 供发布路径自测：当前副本 id。 */
    public String replicaId() {
        return eventBus.replicaId();
    }

    public CatalogRuntimeService catalogRuntime() {
        return catalogRuntime;
    }
}
