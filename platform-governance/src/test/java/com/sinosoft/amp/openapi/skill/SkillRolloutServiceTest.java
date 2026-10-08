package com.sinosoft.amp.openapi.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRolloutServiceTest {

    @Test
    void percentZeroNeverHits() {
        assertFalse(SkillRolloutService.stableHit("s1", "u1", 0));
    }

    @Test
    void percentFullAlwaysHits() {
        assertTrue(SkillRolloutService.stableHit("s1", "u1", 100));
    }

    @Test
    void stableHashIsDeterministic() {
        boolean a = SkillRolloutService.stableHit("skill-a", "user-1", 50);
        boolean b = SkillRolloutService.stableHit("skill-a", "user-1", 50);
        assertTrue(a == b);
    }

    @Test
    void mixedUsersCoverPartialRollout() {
        int hits = 0;
        for (int i = 0; i < 100; i++) {
            if (SkillRolloutService.stableHit("s", "u" + i, 30)) {
                hits++;
            }
        }
        assertTrue(hits > 5 && hits < 80, "hits=" + hits);
    }
}
