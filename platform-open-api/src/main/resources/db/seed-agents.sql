-- 智能体目录种子（可选）：不经过 Java CatalogBootstrap，直接落库。
-- 与 scripts/provision_agents.py 等价的目录侧数据；知识/技能需走管理 API（含向量入库）。
-- 幂等：ON CONFLICT DO NOTHING / 仅在缺失时插入。
-- 租户默认 acme；模型默认 deepseek:deepseek-flash，可按需改写。

-- ========== demo ==========
INSERT INTO agent (tenant_id, agent_id, name, status, created_at)
VALUES ('acme', 'demo', 'demo', 'ACTIVE', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

INSERT INTO agent_version
  (tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at)
SELECT 'acme', 'demo', 1, 'PUBLISHED', 'deepseek:deepseek-flash',
       '你是一个乐于助人的 AI 助手。用中文简洁、专业地回答；不确定时说明并追问，不要编造。',
       NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM agent_version WHERE tenant_id='acme' AND agent_id='demo' AND version=1
);

INSERT INTO endpoint_release (tenant_id, agent_id, release_version, updated_at)
SELECT 'acme', 'demo', 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM endpoint_release WHERE tenant_id='acme' AND agent_id='demo'
);

INSERT INTO agent_permission_mode (tenant_id, agent_id, mode, updated_at)
VALUES ('acme', 'demo', 'DEFAULT', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

INSERT INTO business_tool (tenant_id, tool_name, description, kind, status, config)
VALUES
  ('acme', 'safe_lookup', '通用只读查询示例', 'IN_PROCESS', 'ENABLED', NULL),
  ('acme', 'dangerous_write', '危险写操作示例（需 HITL 确认）', 'IN_PROCESS', 'ENABLED', NULL)
ON CONFLICT (tenant_id, tool_name) DO NOTHING;

INSERT INTO permission_rule (tenant_id, agent_id, tool_name, rule_content, behavior, source)
VALUES
  ('acme', 'demo', 'safe_lookup', '*', 'ALLOW', 'platform'),
  ('acme', 'demo', 'knowledge_search', '*', 'ALLOW', 'platform'),
  ('acme', 'demo', 'dangerous_write', '*', 'ASK', 'platform')
ON CONFLICT (tenant_id, agent_id, tool_name, rule_content, behavior) DO NOTHING;

-- ========== console-bot ==========
-- 系统提示词较长，请用 scripts/provision_agents.py 写入完整版；
-- 下面仅落目录壳，sys_prompt 可在管理台「编辑 Agent」补全后发布。
INSERT INTO agent (tenant_id, agent_id, name, status, created_at)
VALUES ('acme', 'console-bot', '控制台智能机器人', 'ACTIVE', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

INSERT INTO agent_version
  (tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at)
SELECT 'acme', 'console-bot', 1, 'PUBLISHED', 'deepseek:deepseek-flash',
       '你是 AI 中台控制台的智能机器人「小助」。中文优先；优先调用工具拿实时数据；危险写操作走 HITL。',
       NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM agent_version WHERE tenant_id='acme' AND agent_id='console-bot' AND version=1
);

INSERT INTO endpoint_release (tenant_id, agent_id, release_version, updated_at)
SELECT 'acme', 'console-bot', 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM endpoint_release WHERE tenant_id='acme' AND agent_id='console-bot'
);

INSERT INTO agent_permission_mode (tenant_id, agent_id, mode, updated_at)
VALUES ('acme', 'console-bot', 'DEFAULT', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

-- ========== biz-assist ==========
INSERT INTO agent (tenant_id, agent_id, name, status, created_at)
VALUES ('acme', 'biz-assist', '登录平台业务助手', 'ACTIVE', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;

INSERT INTO agent_version
  (tenant_id, agent_id, version, status, model_id, sys_prompt, workspace, created_at, published_at)
SELECT 'acme', 'biz-assist', 1, 'PUBLISHED', 'deepseek:deepseek-flash',
       '你是登录平台业务助手「业务小助」。只做查询与解释；写操作引导到页面手工操作。',
       NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM agent_version WHERE tenant_id='acme' AND agent_id='biz-assist' AND version=1
);

INSERT INTO endpoint_release (tenant_id, agent_id, release_version, updated_at)
SELECT 'acme', 'biz-assist', 1, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM endpoint_release WHERE tenant_id='acme' AND agent_id='biz-assist'
);

INSERT INTO agent_permission_mode (tenant_id, agent_id, mode, updated_at)
VALUES ('acme', 'biz-assist', 'DEFAULT', CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, agent_id) DO NOTHING;
