package com.sinosoft.amp.openapi.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 技能灰度：环境过滤 + 按 userId 哈希的百分比放量。
 * amp.skill.canary.*
 */
@ConfigurationProperties(prefix = "amp.skill.canary")
public record SkillCanaryProperties(
        /** 当前运行环境名：dev / staging / prod；空则不做环境过滤 */
        String environment,
        /** 默认放量 0–100 */
        Integer defaultPercent,
        /** 按技能名覆盖放量 */
        Map<String, Integer> percents,
        /** 仅允许这些环境看到技能（技能未声明 environments 时用） */
        String allowedEnvironments
) {
    public SkillCanaryProperties {
        if (defaultPercent == null) {
            defaultPercent = 100;
        }
    }

    public int percentFor(String skillName) {
        if (percents != null && skillName != null && percents.containsKey(skillName)) {
            Integer p = percents.get(skillName);
            return p == null ? 100 : clamp(p);
        }
        return clamp(defaultPercent);
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(100, p));
    }
}
