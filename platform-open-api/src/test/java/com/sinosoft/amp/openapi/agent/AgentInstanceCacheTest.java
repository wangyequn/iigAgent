package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.InMemoryAgentCatalogRepository;
import com.sinosoft.amp.openapi.catalog.VersionStatus;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentInstanceCacheTest {

    private final AtomicInteger builds = new AtomicInteger();

    private AgentInstanceCreator creator;
    private AgentInstanceCache cache;

    @BeforeEach
    void setUp() {
        builds.set(0);
        creator = (tenant, agentId, version, modelId, sysPrompt, workspace) -> {
            builds.incrementAndGet();
            return mock(HarnessAgent.class);
        };
        cache = new AgentInstanceCache(creator, 10, 50);
    }

    @Test
    void lazyCreatesSingleInstancePerKey() {
        AgentInstance a = cache.getOrCreate("acme", "demo", 1, "m", "p", "w");
        AgentInstance b = cache.getOrCreate("acme", "demo", 1, "m", "p", "w");
        assertEquals(1, builds.get());
        assertEquals(a.cacheKey(), b.cacheKey());
        assertEquals(1, cache.size());
    }

    @Test
    void switchReleaseDrainsOldAndBlocksNewTrafficOnOldKey() {
        AgentInstance v1 = cache.getOrCreate("acme", "demo", 1, "m", "p", "w");
        AgentInstance v2 = cache.getOrCreate("acme", "demo", 2, "m", "p", "w");
        cache.switchRelease("acme", "demo", 1, 2);
        assertTrue(cache.isDraining("acme", "demo", 1));
        assertFalse(cache.isDraining("acme", "demo", 2));

        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> cache.getOrCreate("acme", "demo", 1, "m", "p", "w"));

        // v1 空闲 → sweep 立即 evict
        cache.sweepDrained();
        assertTrue(v1.isClosed());
        assertFalse(v2.isClosed());
        assertEquals(1, cache.size());
    }

    @Test
    void drainWaitsWhileInFlight() {
        AgentInstance v1 = cache.getOrCreate("acme", "demo", 1, "m", "p", "w");
        cache.getOrCreate("acme", "demo", 2, "m", "p", "w");
        cache.switchRelease("acme", "demo", 1, 2);

        AgentInstanceCache.Lease lease = cache.lease(v1);
        cache.sweepDrained();
        assertFalse(v1.isClosed());
        assertTrue(cache.isDraining("acme", "demo", 1));

        lease.close();
        cache.sweepDrained();
        assertTrue(v1.isClosed());
        assertFalse(cache.isDraining("acme", "demo", 1));
    }

    @Test
    void switchRequiresTargetReady() {
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> cache.switchRelease("acme", "demo", 1, 99));
    }
}

class CatalogRuntimeServiceTest {

    private InMemoryAgentCatalogRepository repo;
    private AgentInstanceCache cache;
    private CatalogRuntimeService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAgentCatalogRepository();
        AgentInstanceCreator creator = (tenant, agentId, version, modelId, sysPrompt, workspace) -> mock(HarnessAgent.class);
        cache = new AgentInstanceCache(creator, 10, 50);
        service = new CatalogRuntimeService(repo, cache);

        repo.upsertAgent(new AgentRecord("acme", "demo", "demo", AgentStatus.ACTIVE, Instant.now()));
        repo.upsertVersion(new com.sinosoft.amp.openapi.catalog.AgentVersionRecord(
                "acme", "demo", 1, VersionStatus.PUBLISHED,
                "deepseek:deepseek-flash", "sys", "ws", Instant.now(), Instant.now()));
        repo.upsertRelease("acme", "demo", 1);
    }

    @Test
    void publishV2SwitchesPointerAndDrainsV1() {
        // 预热 v1 并保持 in-flight，避免 publish 末尾 sweep 立即 evict
        AgentInstance warmV1 = service.resolve("acme", "demo");
        assertEquals("acme:demo:1", warmV1.cacheKey());
        AgentInstanceCache.Lease inFlight = cache.lease(warmV1);

        repo.upsertVersion(new com.sinosoft.amp.openapi.catalog.AgentVersionRecord(
                "acme", "demo", 2, VersionStatus.DRAFT,
                "deepseek:deepseek-flash", "sys-v2", "ws", Instant.now(), null));

        CatalogRuntimeService.PublishResult result = service.publishAndSwitch("acme", "demo", 2);

        assertEquals(2, result.releaseVersion());
        assertEquals(1, result.previousVersion());
        assertEquals(2, service.releaseVersion("acme", "demo").orElse(-1));
        assertTrue(cache.isDraining("acme", "demo", 1));
        assertEquals(VersionStatus.DRAINING,
                repo.findVersion("acme", "demo", 1).orElseThrow().status());
        assertEquals(VersionStatus.PUBLISHED,
                repo.findVersion("acme", "demo", 2).orElseThrow().status());

        // 新流量解析到 v2
        AgentInstance resolved = service.resolve("acme", "demo");
        assertEquals("acme:demo:2", resolved.cacheKey());

        // in-flight 结束后才允许排空完成
        cache.sweepDrained();
        assertFalse(warmV1.isClosed());
        inFlight.close();
        cache.sweepDrained();
        assertTrue(warmV1.isClosed());
        assertFalse(cache.isDraining("acme", "demo", 1));
    }

    @Test
    void publishMissingVersionFailsWithoutSwitch() {
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> service.publishAndSwitch("acme", "demo", 7));
        assertEquals(1, service.releaseVersion("acme", "demo").orElse(-1));
    }

    @Test
    void createVersionAutoIncrements() {
        var v = service.createVersion("acme", "demo", null, "m", "p", null);
        assertEquals(2, v.version());
        assertEquals(VersionStatus.DRAFT, v.status());
        assertEquals("m", v.modelId());
    }

    @Test
    void resolveRequiresRelease() {
        repo.upsertAgent(new AgentRecord("acme", "other", "other", AgentStatus.ACTIVE, Instant.now()));
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> service.resolve("acme", "other"));
    }
}
