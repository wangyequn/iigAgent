package com.sinosoft.amp.openapi.skill;

import java.time.Instant;

/**
 * S5 技能市场元数据（未审 DRAFT 不可见，APPROVED 进只读分发）。
 * visibilityEnv / rolloutPercent 供 Canary 灰度（空=全量可见）。
 */
public record SkillRecord(
        String id,
        String tenantId,
        String name,
        String version,
        String description,
        String skillContent,
        String source,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String visibilityEnv,
        Integer rolloutPercent
) {
    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String ARCHIVED = "ARCHIVED";

    public SkillRecord(
            String id, String tenantId, String name, String version, String description,
            String skillContent, String source, String status, String createdBy,
            Instant createdAt, Instant updatedAt) {
        this(id, tenantId, name, version, description, skillContent, source, status, createdBy,
                createdAt, updatedAt, null, 100);
    }
}

