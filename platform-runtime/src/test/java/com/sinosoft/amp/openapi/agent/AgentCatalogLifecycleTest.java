package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.catalog.InMemoryAgentCatalogRepository;
import com.sinosoft.amp.openapi.catalog.VersionStatus;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 智能体引擎生命周期测试：创建 → 配置版本 → 发布切流 → 下架/删除，
 * 以及 agentId 合法性、版本冲突、禁用拒绝、租户隔离。
 */
class AgentCatalogLifecycleTest {

    private InMemoryAgentCatalogRepository repo;
    private AgentInstanceCache cache;
    private CatalogRuntimeService service;
    private final AtomicInteger builds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        repo = new InMemoryAgentCatalogRepository();
        builds.set(0);
        cache = new AgentInstanceCache(
                (tenant, agentId, version, modelId, sysPrompt, workspace) -> {
                    builds.incrementAndGet();
                    return mock(HarnessAgent.class);
                }, 10, 1000);
        service = new CatalogRuntimeService(repo, cache);
    }

    @Test
    void createAgentRejectsInvalidId() {
        CatalogException e1 = assertThrows(CatalogException.class,
                () -> service.createAgent("acme", "bad id!", "x"));
        assertEquals("AGENT_ID_INVALID", e1.code());

        CatalogException e2 = assertThrows(CatalogException.class,
                () -> service.createAgent("acme", "a".repeat(65), "x"));
        assertEquals("AGENT_ID_INVALID", e2.code());

        assertNotNull(service.createAgent("acme", "agent_01", "客服"));
    }

    @Test
    void duplicateAgentRejected() {
        service.createAgent("acme", "demo", "Demo");
        CatalogException e = assertThrows(CatalogException.class,
                () -> service.createAgent("acme", "demo", "Demo2"));
        assertEquals("AGENT_EXISTS", e.code());
    }

    @Test
    void updateAgentTogglesEnabled() {
        service.createAgent("acme", "demo", "Demo");
        AgentRecord disabled = service.updateAgent("acme", "demo", null, false);
        assertEquals(AgentStatus.DISABLED, disabled.status());

        CatalogException e = assertThrows(CatalogException.class,
                () -> service.resolve("acme", "demo"));
        assertEquals("AGENT_DISABLED", e.code());

        AgentRecord enabled = service.updateAgent("acme", "demo", "改名", true);
        assertEquals(AgentStatus.ACTIVE, enabled.status());
        assertEquals("改名", enabled.name());
    }

    @Test
    void versionLifecycleDraftPublishDrain() {
        service.createAgent("acme", "demo", "Demo");
        AgentVersionRecord v1 = service.createVersion("acme", "demo", null, "deepseek:deepseek-flash", "p1", "w");
        assertEquals(1, v1.version());
        assertEquals(VersionStatus.DRAFT, v1.status());

        AgentVersionRecord v2 = service.createVersion("acme", "demo", null, "deepseek:deepseek-flash", "p2", "w");
        assertEquals(2, v2.version());

        CatalogException dup = assertThrows(CatalogException.class,
                () -> service.createVersion("acme", "demo", 2, "m", "p", "w"));
        assertEquals("VERSION_EXISTS", dup.code());

        CatalogRuntimeService.PublishResult r1 =
                service.publishAndSwitch("acme", "demo", 1);
        assertEquals(1, r1.releaseVersion());
        assertEquals(1, service.releaseVersion("acme", "demo").orElse(-1));
        assertEquals(VersionStatus.PUBLISHED,
                repo.findVersion("acme", "demo", 1).orElseThrow().status());

        // 解析当前发布版本，命中已构建实例
        AgentInstance resolved = service.resolve("acme", "demo");
        assertNotNull(resolved);
        assertEquals("acme:demo:1", resolved.cacheKey());
        assertEquals(1, builds.get());

        // 发布 v2 → v1 进入排空；publishAndSwitch 末尾 sweep 会立即驱逐空闲实例
        service.publishAndSwitch("acme", "demo", 2);
        assertEquals(2, service.releaseVersion("acme", "demo").orElse(-1));
        assertEquals(VersionStatus.DRAINING,
                repo.findVersion("acme", "demo", 1).orElseThrow().status());
        assertEquals(VersionStatus.PUBLISHED,
                repo.findVersion("acme", "demo", 2).orElseThrow().status());
        // 空闲旧实例已被 sweep 驱逐，缓存只保留 v2
        assertEquals(1, cache.size());
        AgentInstance resolvedV2 = service.resolve("acme", "demo");
        assertEquals("acme:demo:2", resolvedV2.cacheKey());
    }

    @Test
    void publishRequiresExistingDraftAndActiveAgent() {
        service.createAgent("acme", "demo", "Demo");
        CatalogException noVer = assertThrows(CatalogException.class,
                () -> service.publishAndSwitch("acme", "demo", 9));
        assertEquals("VERSION_NOT_FOUND", noVer.code());

        service.createVersion("acme", "demo", 1, "m", "p", "w");
        service.updateAgent("acme", "demo", null, false);
        CatalogException disabled = assertThrows(CatalogException.class,
                () -> service.publishAndSwitch("acme", "demo", 1));
        assertEquals("AGENT_DISABLED", disabled.code());
    }

    @Test
    void resolveWithoutReleaseFails() {
        service.createAgent("acme", "demo", "Demo");
        CatalogException e = assertThrows(CatalogException.class,
                () -> service.resolve("acme", "demo"));
        assertEquals("NO_RELEASE", e.code());
    }

    @Test
    void tenantIsolationInCatalog() {
        service.createAgent("acme", "demo", "Demo");
        CatalogException e = assertThrows(CatalogException.class,
                () -> service.updateAgent("other", "demo", null, true));
        assertEquals("AGENT_NOT_FOUND", e.code());
        assertTrue(service.listAgents("other").isEmpty());
        assertEquals(1, service.listAgents("acme").size());
    }

    @Test
    void deleteAgentRemovesAll() {
        service.createAgent("acme", "demo", "Demo");
        service.createVersion("acme", "demo", 1, "m", "p", "w");
        service.publishAndSwitch("acme", "demo", 1);
        service.deleteAgent("acme", "demo");
        assertTrue(service.listAgents("acme").isEmpty());
        assertTrue(service.releaseVersion("acme", "demo").isEmpty());
        // 删除后目录查询按设计抛 AGENT_NOT_FOUND
        CatalogException e = assertThrows(CatalogException.class,
                () -> service.listVersions("acme", "demo"));
        assertEquals("AGENT_NOT_FOUND", e.code());
    }
}
