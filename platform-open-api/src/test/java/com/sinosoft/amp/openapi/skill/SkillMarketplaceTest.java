package com.sinosoft.amp.openapi.skill;

import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.agent.AgentInstanceCreator;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SkillMarketplaceTest {

    private InMemorySkillRepo repo;
    private AgentInstanceCache cache;
    private SkillAdminService admin;
    private MarketplaceSkillRepository market;

    @BeforeEach
    void setUp() {
        repo = new InMemorySkillRepo();
        AgentInstanceCreator creator = (tenant, a, v, m, s, w) -> mock(io.agentscope.harness.agent.HarnessAgent.class);
        cache = new AgentInstanceCache(creator, 10, 50);
        admin = new SkillAdminService(repo, cache);
        market = new MarketplaceSkillRepository(repo, "acme");
    }

    @Test
    void draftNotVisibleUntilApproved() {
        admin.upload("acme", "order-style", "1.0.0", "desc", "# skill", "u1");
        assertTrue(market.getAllSkills().isEmpty());
        assertTrue(market.getAllSkillNames().isEmpty());
        assertFalse(market.skillExists("order-style"));
    }

    @Test
    void approveThenVisibleAndWriteableFalse() {
        SkillRecord rec = admin.upload("acme", "order-style", "1.0.0", "desc", "# body", "u1");
        SkillRecord approved = admin.approve("acme", rec.id(), "reviewer", "ok");
        assertEquals(SkillRecord.APPROVED, approved.status());

        List<AgentSkill> skills = market.getAllSkills();
        assertEquals(1, skills.size());
        assertEquals("order-style", skills.get(0).getName());
        assertTrue(market.skillExists("order-style"));
        assertFalse(market.isWriteable());
        assertFalse(market.save(List.of(), true));
        assertFalse(market.delete("order-style"));
    }

    @Test
    void rejectNotVisible() {
        SkillRecord rec = admin.upload("acme", "bad-skill", "1.0.0", "d", "# x", "u1");
        admin.reject("acme", rec.id(), "rev", "no");
        assertTrue(market.getAllSkills().isEmpty());
    }

    @Test
    void approveEvictsInstances() {
        SkillRecord rec = admin.upload("acme", "order-style", "1.0.0", "d", "# x", "u1");
        cache.getOrCreate("acme", "demo", 1, "m", "p", "w");
        assertEquals(1, cache.size());
        admin.approve("acme", rec.id(), "rev", "ok");
        assertEquals(0, cache.size());
    }

    @Test
    void foreignTenantCannotApprove() {
        SkillRecord rec = admin.upload("acme", "s1", "1.0.0", "d", "# x", "u1");
        assertThrows(CatalogException.class, () -> admin.approve("other", rec.id(), "r", null));
    }

    static class InMemorySkillRepo implements SkillCatalogRepository {
        private final Map<String, SkillRecord> byId = new HashMap<>();
        private final List<MapReview> reviews = new ArrayList<>();

        @Override
        public Optional<SkillRecord> find(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<SkillRecord> findByNameVersion(String tenantId, String name, String version) {
            return byId.values().stream()
                    .filter(s -> s.tenantId().equals(tenantId) && s.name().equals(name) && s.version().equals(version))
                    .findFirst();
        }

        @Override
        public List<SkillRecord> list(String tenantId) {
            return byId.values().stream().filter(s -> s.tenantId().equals(tenantId)).toList();
        }

        @Override
        public List<SkillRecord> listByStatus(String tenantId, String status) {
            return list(tenantId).stream().filter(s -> s.status().equals(status)).toList();
        }

        @Override
        public void insert(SkillRecord skill) {
            byId.put(skill.id(), skill);
        }

        @Override
        public void updateStatus(String id, String status, Instant updatedAt) {
            SkillRecord old = byId.get(id);
            if (old == null) return;
            byId.put(id, new SkillRecord(old.id(), old.tenantId(), old.name(), old.version(), old.description(),
                    old.skillContent(), old.source(), status, old.createdBy(), old.createdAt(), updatedAt));
        }

        @Override
        public void recordReview(String reviewId, String skillId, String result, String reviewer, String note) {
            reviews.add(new MapReview(reviewId, skillId, result, reviewer, note, Instant.now()));
        }

        @Override
        public List<MapReview> listReviews(String skillId) {
            return reviews.stream().filter(r -> r.skillId().equals(skillId)).toList();
        }
    }
}
