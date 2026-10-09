package com.sinosoft.amp.openapi.skill;

import com.sinosoft.amp.openapi.agent.AgentInstanceCache;
import com.sinosoft.amp.openapi.catalog.CatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * S5 技能治理：上传草稿 → 审核（人工）→ APPROVED 进只读市场。
 * 审核通过后驱逐实例缓存，保证「审后下轮生效」。
 */
@Service
public class SkillAdminService {

    private static final Logger log = LoggerFactory.getLogger(SkillAdminService.class);

    private final SkillCatalogRepository catalog;
    private final AgentInstanceCache instanceCache;
    private final com.sinosoft.amp.openapi.ops.ReleaseEventBus eventBus;

    public SkillAdminService(SkillCatalogRepository catalog, AgentInstanceCache instanceCache) {
        this(catalog, instanceCache, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillAdminService(
            SkillCatalogRepository catalog,
            AgentInstanceCache instanceCache,
            com.sinosoft.amp.openapi.ops.ReleaseEventBus eventBus) {
        this.catalog = catalog;
        this.instanceCache = instanceCache;
        this.eventBus = eventBus;
    }

    private void evictAndBroadcast(String tenantId, String reason) {
        instanceCache.evictTenant(tenantId);
        if (eventBus != null) {
            eventBus.publishTenantEvict(tenantId, reason);
        }
    }

    public SkillRecord upload(String tenantId, String name, String version, String description,
                              String skillContent, String createdBy) {
        return upload(tenantId, name, version, description, skillContent, createdBy, "marketplace");
    }

    /**
     * 外部注册源导入（Nacos Skill Registry 等）：仍落 DRAFT，须人工 approve 后才进只读市场。
     */
    public SkillRecord upload(String tenantId, String name, String version, String description,
                              String skillContent, String createdBy, String source) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new CatalogException("SKILL_NAME_INVALID", "name must match [A-Za-z0-9_-]{1,64}");
        }
        if (skillContent == null || skillContent.isBlank()) {
            throw new CatalogException("SKILL_CONTENT_REQUIRED", "skillContent is required");
        }
        String ver = version == null || version.isBlank() ? "1.0.0" : version.trim();
        Optional<SkillRecord> existing = catalog.findByNameVersion(tenantId, name, ver);
        String id = existing.map(SkillRecord::id).orElse(UUID.randomUUID().toString().replace("-", ""));
        SkillRecord rec = new SkillRecord(
                id, tenantId, name, ver,
                description == null ? name : description,
                skillContent, source == null || source.isBlank() ? "marketplace" : source,
                SkillRecord.DRAFT, createdBy,
                Instant.now(), Instant.now());
        catalog.insert(rec);
        log.info("skill uploaded id={} name={} ver={} status=DRAFT source={}", id, name, ver, rec.source());
        return rec;
    }

    public List<SkillRecord> list(String tenantId) {
        return catalog.list(tenantId);
    }

    public List<SkillRecord> listVisible(String tenantId) {
        return catalog.listByStatus(tenantId, SkillRecord.APPROVED);
    }

    public SkillRecord get(String id) {
        return catalog.find(id).orElseThrow(() -> new CatalogException("SKILL_NOT_FOUND", "skill not found: " + id));
    }

    /**
     * 审核通过：写 skill_review，状态 APPROVED，驱逐该租户 Agent 实例缓存。
     * 空壳/占位内容禁止通过（Nacos 等外部源导入的卡片只带占位说明，须补正文后再审）。
     */
    public SkillRecord approve(String tenantId, String skillId, String reviewer, String note) {
        SkillRecord skill = requireOwned(tenantId, skillId);
        requireSubstantiveContent(skill);
        if (SkillRecord.APPROVED.equals(skill.status())) {
            return skill;
        }
        catalog.recordReview(UUID.randomUUID().toString().replace("-", ""),
                skillId, "APPROVE", reviewer, note);
        catalog.updateStatus(skillId, SkillRecord.APPROVED, Instant.now());
        // 「审后下轮生效」：驱逐缓存，下次请求重建实例并加载新技能
        evictAndBroadcast(tenantId, "skill-approve:" + skillId);
        log.info("skill approved id={} name={} evicted-instances tenant={}", skillId, skill.name(), tenantId);
        return get(skillId);
    }

    /** 拒绝空内容与占位内容（如 Nacos 导入的 "attach content before approve" 卡片）。 */
    private static void requireSubstantiveContent(SkillRecord skill) {
        String content = skill.skillContent() == null ? "" : skill.skillContent();
        String stripped = content.strip();
        boolean placeholder = stripped.contains("attach content before approve")
                || stripped.lines().allMatch(l -> l.isBlank() || l.stripLeading().startsWith("#"));
        if (stripped.isBlank() || placeholder) {
            throw new CatalogException("SKILL_CONTENT_PLACEHOLDER",
                    "skill content is empty or placeholder; attach real content before approve");
        }
    }

    public SkillRecord reject(String tenantId, String skillId, String reviewer, String note) {
        SkillRecord skill = requireOwned(tenantId, skillId);
        catalog.recordReview(UUID.randomUUID().toString().replace("-", ""),
                skillId, "REJECT", reviewer, note);
        catalog.updateStatus(skillId, SkillRecord.REJECTED, Instant.now());
        evictAndBroadcast(tenantId, "skill-reject:" + skillId);
        log.info("skill rejected id={} name={}", skillId, skill.name());
        return get(skillId);
    }

    public SkillRecord archive(String tenantId, String skillId) {
        SkillRecord skill = requireOwned(tenantId, skillId);
        catalog.updateStatus(skillId, SkillRecord.ARCHIVED, Instant.now());
        evictAndBroadcast(tenantId, "skill-archive:" + skillId);
        return get(skillId);
    }

    public List<SkillCatalogRepository.MapReview> reviews(String skillId) {
        return catalog.listReviews(skillId);
    }

    private SkillRecord requireOwned(String tenantId, String skillId) {
        SkillRecord skill = catalog.find(skillId)
                .orElseThrow(() -> new CatalogException("SKILL_NOT_FOUND", "skill not found: " + skillId));
        if (!skill.tenantId().equals(tenantId)) {
            throw new CatalogException("FORBIDDEN", "skill tenant mismatch");
        }
        return skill;
    }
}
