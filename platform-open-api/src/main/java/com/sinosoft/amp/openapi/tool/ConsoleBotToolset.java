package com.sinosoft.amp.openapi.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sinosoft.amp.openapi.catalog.AgentRecord;
import com.sinosoft.amp.openapi.catalog.AgentVersionRecord;
import com.sinosoft.amp.openapi.governance.PermissionRuleRecord;
import com.sinosoft.amp.openapi.knowledge.KnowledgeStore;
import com.sinosoft.amp.openapi.ops.ReadinessSupport;
import com.sinosoft.amp.openapi.skill.SkillRecord;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台智能机器人工具面：平台全能力查询 + UI 深度交互意图。
 *
 * <p>约定：工具返回 JSON。若含 {@code ui.actions}，前端 ConsoleBot 面板会自动执行
 * （导航 / Toast / 聚焦 / 追问 / 快捷芯片）。
 */
public class ConsoleBotToolset {

    public static final String AGENT_ID = "console-bot";

    private final ConsoleBotSupport support;
    private final String tenantId;
    private final ObjectMapper mapper;

    public ConsoleBotToolset(ConsoleBotSupport support, String tenantId) {
        this.support = support;
        this.tenantId = tenantId == null || tenantId.isBlank() ? "acme" : tenantId;
        this.mapper = support == null ? new ObjectMapper() : support.mapper();
    }

    /* ───────── 平台状态 ───────── */

    @Tool(
            name = "platform_status",
            description = "查询 AI 中台运行状态：就绪探针、限流配置、运行时配置。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String platformStatus() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenantId", tenantId);
            if (support.readiness() != null) {
                ReadinessSupport.ReadyStatus r = support.readiness().probe();
                out.put("ready", r.ready());
                out.put("postgres", r.postgres());
                out.put("redis", r.redis());
            }
            if (support.config() != null) {
                out.put("runtimeConfig", support.config().snapshot());
            }
            return ok(out);
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── Agent 目录 ───────── */

    @Tool(
            name = "list_agents",
            description = "列出当前租户的 Agent（含状态与发布版本）。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listAgents() {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (AgentRecord a : support.catalog().listAgents(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agentId", a.agentId());
                m.put("name", a.name());
                m.put("status", a.status() == null ? null : a.status().name());
                m.put("releaseVersion", support.catalog()
                        .findRelease(tenantId, a.agentId()).map(r -> r.releaseVersion()).orElse(null));
                items.add(m);
            }
            return ok(Map.of("agents", items, "count", items.size()));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "agent_detail",
            description = "查看单个 Agent 详情：基本信息、版本列表、当前发布版本。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String agentDetail(
            @ToolParam(name = "agentId", required = true, description = "Agent ID，如 demo / console-bot")
            Object agentId) {
        try {
            String id = str(agentId);
            if (id.isBlank()) {
                return errSimple("agentId is required");
            }
            var agent = support.catalog().findAgent(tenantId, id).orElse(null);
            if (agent == null) {
                return errSimple("AGENT_NOT_FOUND: " + id);
            }
            List<Map<String, Object>> versions = new ArrayList<>();
            for (AgentVersionRecord v : support.catalog().listVersions(tenantId, id)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", v.version());
                m.put("status", v.status() == null ? null : v.status().name());
                m.put("modelId", v.modelId());
                m.put("createdAt", v.createdAt() == null ? null : v.createdAt().toString());
                versions.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agentId", agent.agentId());
            out.put("name", agent.name());
            out.put("status", agent.status() == null ? null : agent.status().name());
            out.put("releaseVersion", support.catalog()
                    .findRelease(tenantId, id).map(r -> r.releaseVersion()).orElse(null));
            out.put("versions", versions);
            return okWithUi(out, List.of(Map.of(
                    "type", "focus",
                    "entityType", "agent",
                    "entityId", id)));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── 治理 ───────── */

    @Tool(
            name = "list_permissions",
            description = "列出 Agent 的权限规则（ALLOW/ASK/DENY）与模式。只读。agentId 可选，默认 demo。",
            readOnly = true,
            concurrencySafe = true)
    public String listPermissions(
            @ToolParam(name = "agentId", required = false, description = "Agent ID，默认 demo")
            Object agentId) {
        try {
            String id = str(agentId);
            if (id.isBlank()) {
                id = "demo";
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            for (PermissionRuleRecord r : support.governance().listRules(tenantId, id)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("toolName", r.toolName());
                m.put("behavior", r.behavior());
                m.put("ruleContent", r.ruleContent());
                m.put("source", r.source());
                rules.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agentId", id);
            out.put("mode", support.governance().getMode(tenantId, id));
            out.put("rules", rules);
            return okWithUi(out, List.of(Map.of(
                    "type", "navigate",
                    "path", "/governance/permissions",
                    "query", Map.of("agentId", id))));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "list_business_tools",
            description = "列出已登记的业务工具（IN_PROCESS / HTTP）与 MCP 服务器。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listBusinessTools() {
        try {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (BusinessToolRecord t : support.governance().listTools(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("toolName", t.toolName());
                m.put("description", t.description());
                m.put("kind", t.kind());
                m.put("status", t.status());
                tools.add(m);
            }
            List<Map<String, Object>> mcp = new ArrayList<>();
            for (var r : support.governance().listMcp(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.name());
                m.put("transport", r.transport());
                m.put("status", r.status());
                mcp.add(m);
            }
            return ok(Map.of("businessTools", tools, "mcpServers", mcp));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── 技能 / 知识 ───────── */

    @Tool(
            name = "list_skills",
            description = "列出技能市场中的技能（含审核状态）。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listSkills() {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (SkillRecord s : support.skills().list(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("name", s.name());
                m.put("version", s.version());
                m.put("status", s.status());
                m.put("description", s.description());
                items.add(m);
            }
            return okWithUi(Map.of("skills", items, "count", items.size()),
                    List.of(Map.of("type", "navigate", "path", "/skills")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "list_knowledge",
            description = "列出知识库文档。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listKnowledge() {
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (KnowledgeStore.DocRow d : support.knowledge().listDocuments(tenantId)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.id());
                m.put("title", d.title());
                m.put("source", d.source());
                m.put("status", d.status());
                items.add(m);
            }
            return okWithUi(Map.of("documents", items, "count", items.size(),
                    "provider", support.knowledge().providerId()),
                    List.of(Map.of("type", "navigate", "path", "/workbench/knowledge")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "console_knowledge_search",
            description = "在知识库中检索并返回带引用的片段（与 knowledge_search 等价，供控制台机器人使用）。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String knowledgeSearch(
            @ToolParam(name = "query", required = true, description = "检索问题或关键词")
            Object query,
            @ToolParam(name = "topK", required = false, description = "返回条数，默认5")
            Object topK) {
        try {
            String q = str(query);
            if (q.isBlank()) {
                return errSimple("query is required");
            }
            int k = topK == null ? 5 : parseInt(topK, 5);
            return support.knowledge().searchWithCitations(tenantId, q, k);
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── 编排 ───────── */

    @Tool(
            name = "list_workflows",
            description = "列出 Workflow 定义与最近运行。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listWorkflows() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("workflows", support.workflows().listWorkflows());
            out.put("recentRuns", support.workflows().listRuns(10));
            return okWithUi(out, List.of(Map.of("type", "navigate", "path", "/workflows")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "list_teams",
            description = "列出 Team 协作团队。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String listTeams() {
        try {
            return okWithUi(Map.of("teams", support.teams().listTeams()),
                    List.of(Map.of("type", "navigate", "path", "/teams")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── 运营 / 配额 ───────── */

    @Tool(
            name = "usage_summary",
            description = "查询租户配额与用量汇总、最近用量记录。只读。",
            readOnly = true,
            concurrencySafe = true)
    public String usageSummary() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("summary", support.quota().summary(tenantId));
            out.put("recentUsage", support.quota().recentUsage(tenantId, 10));
            return okWithUi(out, List.of(Map.of("type", "navigate", "path", "/ops/billing")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    @Tool(
            name = "list_audit",
            description = "查看最近审计日志。只读。limit 默认 20。",
            readOnly = true,
            concurrencySafe = true)
    public String listAudit(
            @ToolParam(name = "limit", required = false, description = "返回条数，默认20")
            Object limit) {
        try {
            int n = limit == null ? 20 : parseInt(limit, 20);
            return okWithUi(Map.of("audit", support.audit().recent(tenantId, n)),
                    List.of(Map.of("type", "navigate", "path", "/ops/audit")));
        } catch (RuntimeException e) {
            return err(e);
        }
    }

    /* ───────── UI 深度交互 ───────── */

    @Tool(
            name = "ui_navigate",
            description = "指挥控制台 UI 跳转到指定页面。path 为前端路由，如 /agents、/workbench/chat、/ops/billing。"
                    + "页面映射：workbench/chat 智能对话、workbench/approvals 审批、workbench/knowledge 知识库、"
                    + "agents Agent列表、skills 技能市场、workflows 编排、teams 团队、"
                    + "governance/permissions 权限、governance/tools 工具、governance/channels 渠道、"
                    + "governance/external External、governance/iam IAM、ops/* 运营、system/* 系统。",
            readOnly = true,
            concurrencySafe = true)
    public String uiNavigate(
            @ToolParam(name = "path", required = true, description = "前端路由路径，如 /agents")
            Object path,
            @ToolParam(name = "label", required = false, description = "操作说明，展示给用户")
            Object label) {
        String p = str(path).trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("type", "navigate");
        act.put("path", p);
        if (label != null && !str(label).isBlank()) {
            act.put("label", str(label));
        }
        actions.add(act);
        return okWithUi(Map.of("navigatedTo", p), actions);
    }

    @Tool(
            name = "ui_focus",
            description = "在 UI 中聚焦/打开某个实体详情（Agent、技能、知识文档、Workflow 等）。会尽量跳到对应页面并高亮。",
            readOnly = true,
            concurrencySafe = true)
    public String uiFocus(
            @ToolParam(name = "entityType", required = true,
                    description = "实体类型：agent | skill | knowledge | workflow | team | session")
            Object entityType,
            @ToolParam(name = "entityId", required = true, description = "实体 ID")
            Object entityId,
            @ToolParam(name = "label", required = false, description = "展示给用户的说明")
            Object label) {
        String type = str(entityType).trim().toLowerCase();
        String id = str(entityId).trim();
        if (id.isBlank()) {
            return errSimple("entityId is required");
        }
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("type", "focus");
        act.put("entityType", type);
        act.put("entityId", id);
        if (label != null && !str(label).isBlank()) {
            act.put("label", str(label));
        }
        return okWithUi(Map.of("focused", Map.of("entityType", type, "entityId", id)),
                List.of(act));
    }

    @Tool(
            name = "ui_notify",
            description = "在控制台弹出 Toast 通知。level: info | success | warning | error。",
            readOnly = true,
            concurrencySafe = true)
    public String uiNotify(
            @ToolParam(name = "level", required = false, description = "info/success/warning/error，默认 info")
            Object level,
            @ToolParam(name = "message", required = true, description = "通知文案")
            Object message) {
        String msg = str(message).trim();
        if (msg.isBlank()) {
            return errSimple("message is required");
        }
        String lv = str(level).trim().toLowerCase();
        if (lv.isBlank()) {
            lv = "info";
        }
        return okWithUi(Map.of("notified", true),
                List.of(Map.of("type", "toast", "level", lv, "message", msg)));
    }

    @Tool(
            name = "ui_ask_user",
            description = "在机器人面板中向用户展示追问卡片（带可选快捷选项）。用户点选后会作为新消息发送。",
            readOnly = true,
            concurrencySafe = true)
    public String uiAskUser(
            @ToolParam(name = "prompt", required = true, description = "追问文案")
            Object prompt,
            @ToolParam(name = "options", required = false,
                    description = "快捷选项，逗号分隔，如：查看权限,打开知识库,检查用量")
            Object options) {
        String p = str(prompt).trim();
        if (p.isBlank()) {
            return errSimple("prompt is required");
        }
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("type", "askUser");
        act.put("prompt", p);
        String opt = str(options).trim();
        if (!opt.isBlank()) {
            List<String> opts = new ArrayList<>();
            for (String s : opt.split("[,，]")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    opts.add(t);
                }
            }
            if (!opts.isEmpty()) {
                act.put("options", opts);
            }
        }
        return okWithUi(Map.of("asked", true), List.of(act));
    }

    @Tool(
            name = "ui_suggest",
            description = "在机器人面板底部生成一组可点击快捷操作芯片（导航或提问）。",
            readOnly = true,
            concurrencySafe = true)
    public String uiSuggest(
            @ToolParam(name = "actions", required = true,
                    description = "JSON 数组字符串，每项 {\"type\":\"navigate|ask\",\"path\"?:\"/x\",\"text\":\"文案\"}")
            Object actions) {
        String raw = str(actions).trim();
        if (raw.isEmpty()) {
            return errSimple("actions is required");
        }
        try {
            Object parsed = mapper.readValue(raw, Object.class);
            List<Map<String, Object>> acts = new ArrayList<>();
            if (parsed instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        Object typeObj = m.get("type");
                        String type = typeObj == null ? "ask" : String.valueOf(typeObj);
                        a.put("type", "chip".equals(type) ? "chip" : ("navigate".equals(type) ? "chipNavigate" : "chipAsk"));
                        if (m.get("path") != null) {
                            a.put("path", String.valueOf(m.get("path")));
                        }
                        Object textObj = m.get("text");
                        if (textObj == null) {
                            textObj = m.get("label");
                        }
                        a.put("text", textObj == null ? "操作" : String.valueOf(textObj));
                        acts.add(a);
                    }
                }
            }
            if (acts.isEmpty()) {
                return errSimple("actions must be a non-empty JSON array");
            }
            return okWithUi(Map.of("suggested", acts.size()), List.of(Map.of("type", "chips", "items", acts)));
        } catch (Exception e) {
            return errSimple("actions must be valid JSON array: " + e.getMessage());
        }
    }

    /* ───────── helpers ───────── */

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int parseInt(Object o, int fallback) {
        try {
            return Integer.parseInt(str(o).replace(".0", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String ok(Object data) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("data", data);
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"serialize failed\"}";
        }
    }

    private String okWithUi(Object data, List<Map<String, Object>> uiActions) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("data", data);
            out.put("ui", Map.of("actions", uiActions));
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"serialize failed\"}";
        }
    }

    private String err(RuntimeException e) {
        return errSimple(e.getMessage() == null ? e.toString() : e.getMessage());
    }

    private String errSimple(String message) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", message);
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }
}
