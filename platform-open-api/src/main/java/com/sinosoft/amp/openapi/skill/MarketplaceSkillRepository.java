package com.sinosoft.amp.openapi.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * S5 市场只读 SkillRepository：仅分发 APPROVED 技能（writeable=false）。
 * 实现 AgentSkillRepository，供 HarnessAgent.skillRepository() 挂载。
 */
public class MarketplaceSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSkillRepository.class);

    private final SkillCatalogRepository catalog;
    private final String tenantId;
    private volatile boolean writeable = false;

    public MarketplaceSkillRepository(SkillCatalogRepository catalog, String tenantId) {
        this.catalog = catalog;
        this.tenantId = tenantId;
    }

    private static AgentSkill toAgentSkill(SkillRecord r) {
        return AgentSkill.builder()
                .name(r.name())
                .description(r.description() == null ? r.name() : r.description())
                .skillContent(r.skillContent() == null ? "" : r.skillContent())
                .source("marketplace:" + r.id())
                .putMetadata("skillId", r.id())
                .putMetadata("version", r.version())
                .putMetadata("status", r.status())
                // 灰度：SkillCanaryFilter 读取；未配置则全量
                .putMetadata("rolloutPercent", r.rolloutPercent())
                .putMetadata("environments", r.visibilityEnv())
                .build();
    }

    @Override
    public AgentSkill getSkill(String name) {
        return getAllSkills().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        // 只读分发：仅 APPROVED
        List<AgentSkill> out = new ArrayList<>();
        for (SkillRecord r : catalog.listByStatus(tenantId, SkillRecord.APPROVED)) {
            out.add(toAgentSkill(r));
        }
        log.debug("marketplace skills tenant={} count={}", tenantId, out.size());
        return out;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        // 平台强制只读市场
        log.warn("save rejected: marketplace skill repository is writeable=false");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("delete rejected: marketplace skill repository is writeable=false");
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("postgresql-marketplace", "tenant=" + tenantId, false);
    }

    @Override
    public String getSource() {
        return "marketplace";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 锁死只读（生产禁止 autoPromote / write-through）
        this.writeable = false;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    public static Map<String, Object> info(AgentSkillRepository repo) {
        return Map.of(
                "source", repo.getSource(),
                "writable", repo.isWriteable(),
                "info", String.valueOf(repo.getRepositoryInfo()),
                "skillCount", repo.getAllSkillNames().size());
    }
}
