package com.sinosoft.amp.openapi.skill;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillCatalogRepository {

    Optional<SkillRecord> find(String id);

    Optional<SkillRecord> findByNameVersion(String tenantId, String name, String version);

    List<SkillRecord> list(String tenantId);

    List<SkillRecord> listByStatus(String tenantId, String status);

    void insert(SkillRecord skill);

    void updateStatus(String id, String status, Instant updatedAt);

    void recordReview(String reviewId, String skillId, String result, String reviewer, String note);

    List<MapReview> listReviews(String skillId);

    record MapReview(String id, String skillId, String result, String reviewer, String note, Instant createdAt) {
    }
}
