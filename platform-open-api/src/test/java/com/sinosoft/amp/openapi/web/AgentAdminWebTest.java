package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.agent.CatalogRuntimeService;
import com.sinosoft.amp.openapi.catalog.AgentCatalogRepository;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentStatus;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.catalog.EndpointRelease;
import com.sinosoft.amp.openapi.catalog.VersionStatus;
import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.security.AuthContext;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.UnauthorizedException;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证授权 + 智能体引擎 Web 层测试：
 * 租户上下文注入、agent 白名单（403）、未认证（403）、目录租户隔离。
 */
class AgentAdminWebTest {

    private FakeCatalogRepo repo;
    private CatalogRuntimeService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repo = new FakeCatalogRepo();
        service = new CatalogRuntimeService(repo,
                new AgentInstanceCache(
                        (t, a, v, m, p, w) -> mock(HarnessAgent.class), 10, 1000));
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentAdminController(service))
                .setControllerAdvice(new TestAdvice())
                .build();
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private void login(String key, String tenant, List<String> agents, List<String> scopes) {
        AuthContext.set(new AuthProperties.KeyEntry(key, tenant, agents, scopes));
    }

    @Test
    void listAgentsScopedToCurrentTenant() throws Exception {
        repo.upsertAgent(new AgentRecord("acme", "demo", "Demo", AgentStatus.ACTIVE, Instant.now()));
        repo.upsertAgent(new AgentRecord("globex", "other", "Other", AgentStatus.ACTIVE, Instant.now()));
        login("k1", "acme", List.of(), List.of("open", "admin"));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.agents.length()").value(1))
                .andExpect(jsonPath("$.agents[0].agentId").value("demo"));
    }

    @Test
    void versionsRequireAgentWhitelist() throws Exception {
        seedVersion("acme", "demo", 1);
        login("k2", "acme", List.of("other-agent"), List.of("open", "admin"));

        mockMvc.perform(get("/api/v1/agents/demo/versions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void unauthenticatedRejected() throws Exception {
        AuthContext.clear();
        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isForbidden());
    }

    @Test
    void whitelistWildcardAllowsAllAgents() throws Exception {
        seedVersion("acme", "demo", 1);
        login("k3", "acme", List.of("*"), List.of("open", "admin"));

        mockMvc.perform(get("/api/v1/agents/demo/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("demo"))
                .andExpect(jsonPath("$.releaseVersion").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1));
    }

    @Test
    void unknownAgentMapsTo404() throws Exception {
        login("k4", "acme", List.of(), List.of("open", "admin"));
        mockMvc.perform(get("/api/v1/agents/ghost/versions"))
                .andExpect(status().isNotFound());
    }

    private void seedVersion(String tenant, String agentId, int version) {
        repo.upsertAgent(new AgentRecord(tenant, agentId, agentId, AgentStatus.ACTIVE, Instant.now()));
        repo.upsertVersion(new AgentVersionRecord(
                tenant, agentId, version, VersionStatus.PUBLISHED,
                "deepseek:deepseek-flash", "p", "w", Instant.now(), Instant.now()));
        repo.upsertRelease(tenant, agentId, version);
    }

    /** 内存目录实现（平台 runtime 的同名测试类不跨模块发布，此处内联最小版）。 */
    static class FakeCatalogRepo implements AgentCatalogRepository {
        final Map<String, AgentRecord> agents = new HashMap<>();
        final Map<String, AgentVersionRecord> versions = new HashMap<>();
        final Map<String, EndpointRelease> releases = new HashMap<>();

        private static String ak(String t, String a) { return t + "|" + a; }
        private static String vk(String t, String a, int v) { return t + "|" + a + "|" + v; }

        @Override public Optional<AgentRecord> findAgent(String t, String a) {
            return Optional.ofNullable(agents.get(ak(t, a)));
        }
        @Override public List<AgentRecord> listAgents(String t) {
            List<AgentRecord> out = new ArrayList<>();
            agents.forEach((k, v) -> { if (v.tenantId().equals(t)) out.add(v); });
            out.sort((x, y) -> x.agentId().compareToIgnoreCase(y.agentId()));
            return out;
        }
        @Override public void upsertAgent(AgentRecord a) { agents.put(ak(a.tenantId(), a.agentId()), a); }
        @Override public void deleteAgent(String t, String a) { agents.remove(ak(t, a)); }
        @Override public Optional<AgentVersionRecord> findVersion(String t, String a, int v) {
            return Optional.ofNullable(versions.get(vk(t, a, v)));
        }
        @Override public List<AgentVersionRecord> listVersions(String t, String a) {
            List<AgentVersionRecord> out = new ArrayList<>();
            versions.forEach((k, v) -> {
                if (v.tenantId().equals(t) && v.agentId().equals(a)) out.add(v);
            });
            out.sort((x, y) -> Integer.compare(x.version(), y.version()));
            return out;
        }
        @Override public void upsertVersion(AgentVersionRecord v) {
            versions.put(vk(v.tenantId(), v.agentId(), v.version()), v);
        }
        @Override public void updateVersionStatus(String t, String a, int v, VersionStatus s) {
            AgentVersionRecord old = versions.get(vk(t, a, v));
            if (old != null) {
                versions.put(vk(t, a, v), new AgentVersionRecord(
                        old.tenantId(), old.agentId(), old.version(), s,
                        old.modelId(), old.sysPrompt(), old.workspace(),
                        old.createdAt(), old.publishedAt()));
            }
        }
        @Override public Optional<EndpointRelease> findRelease(String t, String a) {
            return Optional.ofNullable(releases.get(ak(t, a)));
        }
        @Override public void upsertRelease(String t, String a, int rv) {
            releases.put(ak(t, a), new EndpointRelease(t, a, rv, Instant.now()));
        }
    }

    /** CatalogException → 4xx；UnauthorizedException → 403 INSUFFICIENT_SCOPE。 */
    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class TestAdvice {
        @org.springframework.web.bind.annotation.ExceptionHandler(CatalogException.class)
        public org.springframework.http.ResponseEntity<Object> handleCatalog(CatalogException e) {
            return org.springframework.http.ResponseEntity.status(
                            "AGENT_NOT_FOUND".equals(e.code()) ? 404 : 400)
                    .body(Map.of("code", e.code(), "message", e.getMessage()));
        }

        @org.springframework.web.bind.annotation.ExceptionHandler(UnauthorizedException.class)
        public org.springframework.http.ResponseEntity<Object> handleUnauthorized(UnauthorizedException e) {
            return org.springframework.http.ResponseEntity.status(403)
                    .body(Map.of("code", "INSUFFICIENT_SCOPE", "message", e.getMessage()));
        }
    }
}
