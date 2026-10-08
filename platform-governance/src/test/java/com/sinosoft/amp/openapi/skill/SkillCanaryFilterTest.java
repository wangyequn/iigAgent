package com.sinosoft.amp.openapi.skill;

import io.agentscope.core.skill.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCanaryFilterTest {

    private static AgentSkill skill(String name) {
        return AgentSkill.builder().name(name).description(name).skillContent("# " + name).build();
    }

    @Test
    void defaultPercentShowsAll() {
        SkillCanaryFilter f = new SkillCanaryFilter(new SkillCanaryProperties(null, 100, null, null));
        assertTrue(f.visible(skill("s1"), null, "u1"));
    }

    @Test
    void zeroPercentHides() {
        SkillCanaryFilter f = new SkillCanaryFilter(new SkillCanaryProperties(null, 0, null, null));
        assertFalse(f.visible(skill("s1"), null, "u1"));
    }

    @Test
    void percentileIsStablePerUser() {
        boolean a1 = SkillCanaryFilter.inPercentile("u1", "skill-x", 50);
        boolean a2 = SkillCanaryFilter.inPercentile("u1", "skill-x", 50);
        assertEquals(a1, a2);
    }

    @Test
    void envFilterBlocksWhenNotAllowed() {
        SkillCanaryFilter f = new SkillCanaryFilter(new SkillCanaryProperties("prod", 100, null, "dev,staging"));
        AgentSkill s = AgentSkill.builder().name("s").description("s").skillContent("x")
                .putMetadata("environments", "dev,staging")
                .build();
        assertFalse(f.visible(s, "prod", "u1"));
        assertTrue(f.visible(s, "dev", "u1"));
    }

    @Test
    void metadataPercentOverridesConfig() {
        SkillCanaryFilter f = new SkillCanaryFilter(new SkillCanaryProperties(null, 100, null, null));
        AgentSkill s = AgentSkill.builder().name("s").description("s").skillContent("x")
                .putMetadata("rolloutPercent", 0)
                .build();
        assertFalse(f.visible(s, null, "u1"));
    }

    @Test
    void filterListAppliesRules() {
        SkillCanaryFilter f = new SkillCanaryFilter(new SkillCanaryProperties(null, 0, java.util.Map.of("keep", 100), null));
        List<AgentSkill> out = f.filter(List.of(skill("keep"), skill("drop")), null);
        assertEquals(1, out.size());
        assertEquals("keep", out.get(0).getName());
    }
}
