package com.sinosoft.amp.openapi.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CanaryFilter 落地：环境过滤 + 按 userId 稳定哈希百分比放量。
 * 技能 metadata 可选：rolloutPercent(0-100)、environments("dev,prod")。
 */
public class SkillCanaryFilter implements SkillVisibilityFilter {

    private final SkillCanaryProperties properties;

    public SkillCanaryFilter(SkillCanaryProperties properties) {
        this.properties = properties == null ? new SkillCanaryProperties(null, 100, null, null) : properties;
    }

    @Override
    public List<AgentSkill> filter(List<AgentSkill> skills, RuntimeContext ctx) {
        if (skills == null || skills.isEmpty()) {
            return skills;
        }
        String env = properties.environment();
        String userId = ctx == null ? "" : String.valueOf(ctx.getUserId());
        List<AgentSkill> out = new ArrayList<>();
        for (AgentSkill s : skills) {
            if (visible(s, env, userId)) {
                out.add(s);
            }
        }
        return out;
    }

    boolean visible(AgentSkill skill, String env, String userId) {
        String name = skill.getName();
        if (!envAllowed(skill, env)) {
            return false;
        }
        int percent = resolvePercent(skill, name);
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        return inPercentile(userId, name, percent);
    }

    private boolean envAllowed(AgentSkill skill, String env) {
        Object meta = skill.getMetadataValue("environments");
        String declared = meta == null ? null : String.valueOf(meta);
        if (declared == null || declared.isBlank() || "null".equals(declared)) {
            String allowed = properties.allowedEnvironments();
            if (allowed == null || allowed.isBlank() || env == null || env.isBlank()) {
                return true;
            }
            return parseSet(allowed).contains(env.toLowerCase(Locale.ROOT));
        }
        if (env == null || env.isBlank()) {
            return true;
        }
        return parseSet(declared).contains(env.toLowerCase(Locale.ROOT));
    }

    private int resolvePercent(AgentSkill skill, String name) {
        Object meta = skill.getMetadataValue("rolloutPercent");
        if (meta instanceof Number n) {
            return clamp(n.intValue());
        }
        if (meta != null && !"null".equals(String.valueOf(meta))) {
            try {
                return clamp(Integer.parseInt(String.valueOf(meta).trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return properties.percentFor(name);
    }

    /** userId+skillName 稳定哈希到 [0,100)。 */
    static boolean inPercentile(String userId, String skillName, int percent) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key = (userId == null ? "" : userId) + "|" + (skillName == null ? "" : skillName);
        int h = 0;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + (b & 0xff);
        }
        int bucket = Math.floorMod(h, 100);
        return bucket < percent;
    }

    private static Set<String> parseSet(String csv) {
        Set<String> set = new LinkedHashSet<>();
        for (String p : csv.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(100, p));
    }

    public SkillCanaryProperties properties() {
        return properties;
    }
}
