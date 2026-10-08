CREATE TABLE IF NOT EXISTS agent (
  tenant_id    VARCHAR(64)  NOT NULL,
  agent_id     VARCHAR(64)  NOT NULL,
  name         VARCHAR(128),
  status       VARCHAR(32)  NOT NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, agent_id)
);

CREATE TABLE IF NOT EXISTS agent_version (
  tenant_id    VARCHAR(64)  NOT NULL,
  agent_id     VARCHAR(64)  NOT NULL,
  version      INT          NOT NULL,
  status       VARCHAR(32)  NOT NULL,
  model_id     VARCHAR(128) NOT NULL,
  sys_prompt   VARCHAR(4000),
  workspace    VARCHAR(512),
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMP,
  PRIMARY KEY (tenant_id, agent_id, version)
);

CREATE TABLE IF NOT EXISTS endpoint_release (
  tenant_id        VARCHAR(64) NOT NULL,
  agent_id         VARCHAR(64) NOT NULL,
  release_version  INT         NOT NULL,
  updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, agent_id)
);

-- S4: 权限三态规则
CREATE TABLE IF NOT EXISTS permission_rule (
  tenant_id      VARCHAR(64)  NOT NULL,
  agent_id       VARCHAR(64)  NOT NULL,
  tool_name      VARCHAR(128) NOT NULL,
  rule_content   VARCHAR(256) NOT NULL DEFAULT '*',
  behavior       VARCHAR(16)  NOT NULL,
  source         VARCHAR(64)  NOT NULL DEFAULT 'platform',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, agent_id, tool_name, rule_content, behavior)
);

-- S4: Agent 权限 Mode
CREATE TABLE IF NOT EXISTS agent_permission_mode (
  tenant_id      VARCHAR(64)  NOT NULL,
  agent_id       VARCHAR(64)  NOT NULL,
  mode           VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, agent_id)
);

-- S4: MCP 服务器登记
CREATE TABLE IF NOT EXISTS mcp_server (
  tenant_id      VARCHAR(64)  NOT NULL,
  name           VARCHAR(128) NOT NULL,
  transport      VARCHAR(32)  NOT NULL,
  command        VARCHAR(256),
  args           VARCHAR(1024),
  url            VARCHAR(512),
  enable_tools   TEXT,
  status         VARCHAR(32)  NOT NULL DEFAULT 'REGISTERED',
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, name)
);

-- Nacos AI Registry：来源标记；nacos 导入不得覆盖 local 种子/手工登记
ALTER TABLE mcp_server ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'local';

-- S4: 业务工具登记（进程内 / MCP / HTTP）
CREATE TABLE IF NOT EXISTS business_tool (
  tenant_id      VARCHAR(64)  NOT NULL,
  tool_name      VARCHAR(128) NOT NULL,
  description    VARCHAR(512),
  kind           VARCHAR(32)  NOT NULL DEFAULT 'IN_PROCESS',
  status         VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  config         VARCHAR(2048),
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, tool_name)
);

-- S4: HITL 审批项
CREATE TABLE IF NOT EXISTS approval_item (
  id               VARCHAR(64)  PRIMARY KEY,
  tenant_id        VARCHAR(64)  NOT NULL,
  agent_id         VARCHAR(64)  NOT NULL,
  user_id          VARCHAR(128) NOT NULL,
  session_id       VARCHAR(128) NOT NULL,
  reply_id         VARCHAR(64),
  tool_call_id     VARCHAR(64),
  tool_name        VARCHAR(128),
  tool_input       TEXT,
  status           VARCHAR(32)  NOT NULL,
  decision_note    VARCHAR(512),
  idempotency_key  VARCHAR(128),
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at      TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_approval_idem
  ON approval_item (idempotency_key);

-- S5: 技能市场元数据
CREATE TABLE IF NOT EXISTS skill (
  id           VARCHAR(64)   PRIMARY KEY,
  tenant_id    VARCHAR(64)   NOT NULL,
  name         VARCHAR(128)  NOT NULL,
  version      VARCHAR(32)   NOT NULL DEFAULT '1.0.0',
  description  VARCHAR(512),
  skill_content TEXT,
  source       VARCHAR(64)   NOT NULL DEFAULT 'marketplace',
  status       VARCHAR(32)   NOT NULL DEFAULT 'DRAFT',
  created_by   VARCHAR(64),
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_skill_tenant_name
  ON skill (tenant_id, name, version);

-- 技能灰度（Canary）
ALTER TABLE skill ADD COLUMN IF NOT EXISTS visibility_env VARCHAR(128);
ALTER TABLE skill ADD COLUMN IF NOT EXISTS rollout_percent INT DEFAULT 100;

-- S5: 技能审核记录
CREATE TABLE IF NOT EXISTS skill_review (
  id           VARCHAR(64)   PRIMARY KEY,
  skill_id     VARCHAR(64)   NOT NULL,
  result       VARCHAR(32)   NOT NULL,
  reviewer     VARCHAR(64),
  note         VARCHAR(512),
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- S6: 知识库文档与切片（Simple RAG + 向量）
CREATE TABLE IF NOT EXISTS knowledge_document (
  id           VARCHAR(64)   PRIMARY KEY,
  tenant_id    VARCHAR(64)   NOT NULL,
  title        VARCHAR(256)  NOT NULL,
  source       VARCHAR(256),
  status       VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
  id           VARCHAR(64)   PRIMARY KEY,
  document_id  VARCHAR(64)   NOT NULL,
  tenant_id    VARCHAR(64)   NOT NULL,
  chunk_index  INT           NOT NULL,
  content      TEXT          NOT NULL,
  embedding    TEXT          NOT NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_kc_tenant_doc ON knowledge_chunk (tenant_id, document_id);

-- S6 optional pgvector: swallow errors when extension/column/index unavailable.
-- Body uses single quotes (not $$) so Spring ScriptUtils does not split on ';' inside the block.
-- Equivalent to: DO $$ BEGIN ...; EXCEPTION WHEN OTHERS THEN NULL; END $$;
DO 'BEGIN
  CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION WHEN OTHERS THEN NULL;
END';

DO 'BEGIN
  ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS embedding_vec VECTOR(1536);
EXCEPTION WHEN OTHERS THEN NULL;
END';

DO 'BEGIN
  CREATE INDEX IF NOT EXISTS ix_kc_embedding_vec ON knowledge_chunk USING hnsw (embedding_vec vector_cosine_ops);
EXCEPTION WHEN OTHERS THEN NULL;
END';

-- S8: External Agent 最小集（注册 / 心跳 / Chat 转发）
CREATE TABLE IF NOT EXISTS external_agent (
  id              VARCHAR(64)  PRIMARY KEY,
  tenant_id       VARCHAR(64)  NOT NULL,
  name            VARCHAR(64)  NOT NULL,
  base_url        VARCHAR(512) NOT NULL,
  callback_key    VARCHAR(128),
  capabilities    VARCHAR(512),
  status          VARCHAR(32)  NOT NULL DEFAULT 'HEALTHY',
  last_heartbeat  TIMESTAMP,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ext_agent_tenant_name
  ON external_agent (tenant_id, name);

-- T9 Remote BaseStore（工作区文件，多副本共享）
CREATE TABLE IF NOT EXISTS base_store (
  path_key   TEXT         NOT NULL,
  item_name  VARCHAR(512) NOT NULL,
  payload    TEXT         NOT NULL,
  version    BIGINT       NOT NULL DEFAULT 1,
  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (path_key, item_name)
);

-- P0 审计：管理面变更 / HITL 确认
CREATE TABLE IF NOT EXISTS audit_log (
  id            BIGSERIAL    PRIMARY KEY,
  tenant_id     VARCHAR(64)  NOT NULL,
  actor         VARCHAR(128),
  action        VARCHAR(64)  NOT NULL,
  resource_type VARCHAR(64),
  resource_id   VARCHAR(128),
  method        VARCHAR(16),
  path          VARCHAR(256),
  detail        TEXT,
  status_code   INT,
  duration_ms   BIGINT,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_audit_tenant_created ON audit_log (tenant_id, created_at DESC);

-- P0 模型凭证（provider 级 API Key；接口返回打码）
CREATE TABLE IF NOT EXISTS model_credential (
  tenant_id  VARCHAR(64)  NOT NULL,
  provider   VARCHAR(64)  NOT NULL,
  api_key    VARCHAR(512) NOT NULL,
  base_url   VARCHAR(512),
  enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, provider)
);

-- S9 计费配额：月度上限（0=不限）+ 用量流水
CREATE TABLE IF NOT EXISTS tenant_quota (
  tenant_id             VARCHAR(64) PRIMARY KEY,
  monthly_token_limit   BIGINT NOT NULL DEFAULT 0,
  monthly_request_limit BIGINT NOT NULL DEFAULT 0,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS usage_ledger (
  id                BIGSERIAL    PRIMARY KEY,
  tenant_id         VARCHAR(64)  NOT NULL,
  agent_id          VARCHAR(64),
  session_id        VARCHAR(128),
  user_id           VARCHAR(128),
  prompt_tokens     INT          NOT NULL DEFAULT 0,
  completion_tokens INT          NOT NULL DEFAULT 0,
  cost_micros       BIGINT       NOT NULL DEFAULT 0,
  source            VARCHAR(32)  NOT NULL DEFAULT 'managed',
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_usage_tenant_created ON usage_ledger (tenant_id, created_at DESC);

-- ===== Phase 2 / S9 =====

-- Workflow 定义与 revision 发布
CREATE TABLE IF NOT EXISTS workflow_def (
  tenant_id     VARCHAR(64)  NOT NULL,
  workflow_id   VARCHAR(64)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(512),
  definition    TEXT         NOT NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, workflow_id)
);

CREATE TABLE IF NOT EXISTS workflow_revision (
  tenant_id     VARCHAR(64)  NOT NULL,
  workflow_id   VARCHAR(64)  NOT NULL,
  revision      INT          NOT NULL,
  definition    TEXT         NOT NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'PUBLISHED',
  published_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, workflow_id, revision)
);

CREATE TABLE IF NOT EXISTS workflow_run (
  id              VARCHAR(64)  PRIMARY KEY,
  tenant_id       VARCHAR(64)  NOT NULL,
  workflow_id     VARCHAR(64)  NOT NULL,
  revision        INT          NOT NULL,
  status          VARCHAR(32)  NOT NULL,
  current_step    VARCHAR(64),
  input_payload   TEXT,
  output_payload  TEXT,
  error_message   VARCHAR(1024),
  created_by      VARCHAR(128),
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_wf_run_tenant_created ON workflow_run (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS workflow_step_run (
  id            VARCHAR(64)  PRIMARY KEY,
  run_id        VARCHAR(64)  NOT NULL,
  step_id       VARCHAR(64)  NOT NULL,
  step_type     VARCHAR(32)  NOT NULL,
  status        VARCHAR(32)  NOT NULL,
  input_payload TEXT,
  output_payload TEXT,
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_wf_step_run ON workflow_step_run (run_id);

-- Team 最小集（完整调度对接 AgentScope Service）
CREATE TABLE IF NOT EXISTS team (
  tenant_id   VARCHAR(64)  NOT NULL,
  team_id     VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  leader_agent_id VARCHAR(64),
  description VARCHAR(512),
  status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, team_id)
);

CREATE TABLE IF NOT EXISTS team_member (
  tenant_id   VARCHAR(64)  NOT NULL,
  team_id     VARCHAR(64)  NOT NULL,
  agent_id    VARCHAR(64)  NOT NULL,
  role_name   VARCHAR(64)  NOT NULL DEFAULT 'member',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, team_id, agent_id)
);

-- RBAC：用户 / 角色 / 权限点
CREATE TABLE IF NOT EXISTS iam_user (
  tenant_id  VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(128) NOT NULL,
  display_name VARCHAR(128),
  status     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS iam_role (
  tenant_id  VARCHAR(64)  NOT NULL,
  role_code  VARCHAR(64)  NOT NULL,
  name       VARCHAR(128) NOT NULL,
  description VARCHAR(256),
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, role_code)
);

CREATE TABLE IF NOT EXISTS iam_user_role (
  tenant_id  VARCHAR(64)  NOT NULL,
  user_id    VARCHAR(128) NOT NULL,
  role_code  VARCHAR(64)  NOT NULL,
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id, role_code)
);

CREATE TABLE IF NOT EXISTS iam_role_permission (
  tenant_id     VARCHAR(64)  NOT NULL,
  role_code     VARCHAR(64)  NOT NULL,
  permission    VARCHAR(64)  NOT NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, role_code, permission)
);

-- Channel 绑定
CREATE TABLE IF NOT EXISTS channel_binding (
  tenant_id    VARCHAR(64)  NOT NULL,
  channel_type VARCHAR(32)  NOT NULL,
  agent_id     VARCHAR(64)  NOT NULL,
  config       TEXT,
  secret       VARCHAR(256),
  status       VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, channel_type, agent_id)
);

-- S13: Automation 触发器（Cron / Webhook）
CREATE TABLE IF NOT EXISTS automation_trigger (
  id                 VARCHAR(64)  PRIMARY KEY,
  tenant_id          VARCHAR(64)  NOT NULL,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(32)  NOT NULL DEFAULT 'WEBHOOK',
  cron_expr          VARCHAR(64),
  secret             VARCHAR(256),
  target_workflow_id VARCHAR(64),
  payload            TEXT,
  status             VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  next_due_at        TIMESTAMP,
  last_fired_at      TIMESTAMP,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_automation_tenant ON automation_trigger (tenant_id, type, status);

-- S15: 技能灰度发布
CREATE TABLE IF NOT EXISTS skill_rollout (
  id               VARCHAR(64)  PRIMARY KEY,
  tenant_id        VARCHAR(64)  NOT NULL,
  skill_id         VARCHAR(64)  NOT NULL,
  stage            VARCHAR(32)  NOT NULL,
  rollout_percent  INT          NOT NULL DEFAULT 0,
  visibility_env   VARCHAR(128),
  tenant_whitelist VARCHAR(512),
  status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_skill_rollout ON skill_rollout (tenant_id, skill_id, updated_at DESC);

-- S13: Automation 触发器（Cron / Webhook）
CREATE TABLE IF NOT EXISTS automation_trigger (
  id                 VARCHAR(64)  PRIMARY KEY,
  tenant_id          VARCHAR(64)  NOT NULL,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(32)  NOT NULL DEFAULT 'WEBHOOK',
  cron_expr          VARCHAR(64),
  secret             VARCHAR(256),
  target_workflow_id VARCHAR(64),
  payload            TEXT,
  status             VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  next_due_at        TIMESTAMP,
  last_fired_at      TIMESTAMP,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_automation_tenant ON automation_trigger (tenant_id, type, status);

-- S15: 技能灰度发布
CREATE TABLE IF NOT EXISTS skill_rollout (
  id               VARCHAR(64)  PRIMARY KEY,
  tenant_id        VARCHAR(64)  NOT NULL,
  skill_id         VARCHAR(64)  NOT NULL,
  stage            VARCHAR(32)  NOT NULL,
  rollout_percent  INT          NOT NULL DEFAULT 0,
  visibility_env   VARCHAR(128),
  tenant_whitelist VARCHAR(512),
  status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_skill_rollout ON skill_rollout (tenant_id, skill_id, updated_at DESC);

-- S13: Automation 触发器（Cron / Webhook）
CREATE TABLE IF NOT EXISTS automation_trigger (
  id                 VARCHAR(64)  PRIMARY KEY,
  tenant_id          VARCHAR(64)  NOT NULL,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(32)  NOT NULL DEFAULT 'WEBHOOK',
  cron_expr          VARCHAR(64),
  secret             VARCHAR(256),
  target_workflow_id VARCHAR(64),
  payload            TEXT,
  status             VARCHAR(32)  NOT NULL DEFAULT 'ENABLED',
  next_due_at        TIMESTAMP,
  last_fired_at      TIMESTAMP,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_automation_tenant ON automation_trigger (tenant_id, type, status);

-- S15: 技能灰度发布
CREATE TABLE IF NOT EXISTS skill_rollout (
  id               VARCHAR(64)  PRIMARY KEY,
  tenant_id        VARCHAR(64)  NOT NULL,
  skill_id         VARCHAR(64)  NOT NULL,
  stage            VARCHAR(32)  NOT NULL,
  rollout_percent  INT          NOT NULL DEFAULT 0,
  visibility_env   VARCHAR(128),
  tenant_whitelist VARCHAR(512),
  status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_skill_rollout ON skill_rollout (tenant_id, skill_id, updated_at DESC);
