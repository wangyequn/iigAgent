# AGENTS.md

## 仓库是什么

基于 **AgentScope Java 2.0.3** 的 AI 中台 walking skeleton（**S0–S9 全部落地**）。架构与路线图以 [`docs/AI中台设计文档.md`](docs/AI中台设计文档.md) 为准；可执行行为以代码/配置为准。

| 路径 | 角色 |
| --- | --- |
| `platform-common` | 公共 DTO（如 `ApiError`） |
| `platform-runtime` | 目录/鉴权/会话/Agent 缓存与策略/知识/模型/FS/billing/共享 ops |
| `platform-governance` | 权限/MCP/技能/沙箱 |
| `platform-ops` | Nacos/OTel/Loki/限流/审计过滤 |
| `platform-channel` | Channel/Automation/Orchestration/IAM |
| `platform-open-api` | Spring Boot 入口 + Web 控制器 + 装配（Factory/Bootstrap/ConsoleBot） |
| `amp-starter` | 可抄金路径（Copy 模式，不依赖 platform-*） |
| `platform-console-ui` | Vue3 + Vite Playground，**不在 Maven reactor 内** |

依赖方向：`common → runtime → governance → ops → channel → open-api`。

## 命令

```powershell
# 构建 + 单测（与 CI 一致：.github/workflows/ci.yml）— 208 测
mvn -B verify

# 模块化后本地起服务前：先 install 到本地仓库（scripts/run-api-*.cmd 也依赖此步）
mvn -B -DskipTests install

# 只跑一个测试类
mvn -pl platform-open-api -Dtest=ChatHttpContractTest test

# 本地起后端（默认 http://127.0.0.1:18081）
mvn -pl platform-open-api -am spring-boot:run
# 或：scripts/run-api-18081.cmd

# 前端（需后端已在 18081）
cd platform-console-ui
npm install
npm run dev    # :5173 起，若被占则顺延 5174/5175；/api 代理 → 18081
npm run build  # 产出 dist/

# UI 联调点验（需前端已启动）
cd platform-console-ui
$env:BASE_URL = "http://localhost:5175"
& $env:MIMO_NODE "..\scripts\ui-click-test.mjs"       # 路由/侧栏
& $env:MIMO_NODE "..\scripts\ui-interact-test.mjs"    # 对话发送

# 经管理 API 铺默认智能体（demo / console-bot / biz-assist；幂等）
python scripts/provision_agents.py                   # 需后端在线；可选 --pc-base 登记 biz-assist HTTP 工具
# 或直接执行 SQL：platform-open-api/src/main/resources/db/seed-agents.sql

# 运维脚本（后端需在线）
powershell -File scripts/n4-check.ps1                 # N4 上线自检
powershell -File scripts/smoke-load.ps1 -N 20         # 冒烟压测分位
python scripts/external-sse-mock.py                   # External SSE mock (:19099)
powershell -File scripts/demo-external-stream.ps1     # External 流式透传演示
```

- 仓库**未配置** Java lint / Spotless / ESLint / typecheck；不要臆造这些命令。
- CI 只跑 `mvn -B -q verify`，**不构建** `platform-console-ui`。

## 运行时前置

- **JDK 21**（`pom.xml` `maven.compiler.release=21`）、Maven 3.8+。
- **Redis** `redis://127.0.0.1:6379`（Agent 会话 State）。本机常已有 `redis-server`；Compose 的 Redis 映射到 **16379**（避免占 6379）。
- **PostgreSQL**（S3 目录表）：宿主机默认 **`15432`**（容器内 5432），库/用户 `amp`/`amp`。`spring.sql.init` 执行 `db/schema.sql`；**缺 PG 应用起不来**。
- 模型密钥（按需）：`DEEPSEEK_API_KEY`（默认 `AMP_MODEL_ID=deepseek:deepseek-flash`），或 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`。
- 常用 env：`AMP_SERVER_PORT`（默认 **18081**）、`AMP_REDIS_URI`、`AMP_API_KEY`、`AMP_ADMIN_API_KEY`、`AMP_TENANT_ID`、`AMP_MODEL_ID`、`AMP_WORKSPACE`、`AMP_PG_URI`（默认 `jdbc:postgresql://127.0.0.1:15432/amp`）。
- 本地推荐：本机 Redis:6379 + Docker PG:15432 + **`mvn -DskipTests install`** + 后端 `mvn spring-boot:run` → **http://127.0.0.1:18081**。
- **模块化后启动**：`platform-open-api` 依赖 `runtime/governance/ops/channel` 构件；未 `install` 时 `scripts/run-api-18081.cmd` 会报 Could not resolve `com.sinosoft.amp:platform-*`。
- 全容器：`docker compose up --build` → 宿主机 **18081→容器 8080**（PG **15432**，Redis **16379**）。
- `scripts/start-open-api.{ps1,cmd}` 会**写死 API Key 并改端口占用**；不要把其中密钥抄进代码/文档。
- 单元测试**不依赖** Redis/PG/模型；`mvn -B verify` 即可。

## API 约定（易踩坑）

- 除 `/api/v1/health/**` 外，`/api/*` 均需 `Authorization: Bearer <key>`（`ApiKeyAuthFilter`）。**管理接口也在 `/api/v1/admin/...`**（裸 `/admin` 不会被该过滤器保护）。
- **鉴权 Key / scopes**（`application.yml` → `amp.auth.keys`）：
  - `s0-demo-key`（`AMP_API_KEY`）→ scopes **`[open]`**、租户 `acme`、可访问 agent `demo`。业务对话 / 会话五接口。**不能**调 `/api/v1/admin/**`。
  - `s0-admin-key`（`AMP_ADMIN_API_KEY`）→ scopes **`[open, admin]`**。管理台 / 运维 / **审计** / **配额**。生产请独立轮换。
  - `/api/v1/admin/**` 需要 `admin` scope，其余 `/api/**` 需要 `open`；Open Key 调 admin → `403 INSUFFICIENT_SCOPE`。`admin` **不隐含** open 之外的旁路；scopes 未配置时不限制（兼容旧配置/单测）。
- **tenantId 以 Key 为准**，请求体可不传。所有会话接口强制 `userId` + `sessionId`；`sessionId` 必须匹配 `[A-Za-z0-9_-]{1,128}`，否则 `400 SESSION_CONTEXT_INVALID`。
- 流式路径是 **`POST .../messages:stream`**（冒号）；SSE 用 fetch POST（EventSource 不支持 POST）。
- Chat 按 **endpoint release 指针** 解析当前版本 → `AgentInstanceCache` 懒加载；进程内单例 key=`tenantId:agentId:version`。
- 发布切流（B2）：新实例 build 成功才改指针；旧版 `drain start` → in-flight 归零/超时 → `drain done` 后 evict。
- **`CatalogBootstrap` 不再播种智能体**（仅运维启动：就绪/Nacos/OTel）。Agent/版本/发布一律走管理 API 或 SQL：`scripts/provision_agents.py`、`db/seed-agents.sql`、管理台「新建 Agent」（创建后自动建版本并发布）。
- **审计（audit）**：管理面写操作 / HITL confirm / interrupt 自动写 `audit_log`（`AuditFilter` + `AuditLogService`）；查询 `GET /api/v1/admin/audit?limit=50`（需 **admin** scope）。
- **S4 HITL**：`permission_rule` 落库；危险写工具（如 `dangerous_write`）默认 ASK。流式 `REQUIRE_USER_CONFIRM` → `approval_item` → `POST .../confirm`（metadata ConfirmResult）；重复确认 `409 APPROVAL_ALREADY_RESOLVED`。**非流式**（`POST .../messages` / `confirm`）经事件流收集，暂停时返回 **`409 HITL_CONFIRM_REQUIRED`**（不再是 200 空串），且 `approval_item` 照常落库可查；`confirm` 后若模型重试再次 ASK，同样 409 + 新审批。
- **confirm 必须回填 `ToolUseBlock.content`（易踩坑）**：`ToolExecutor` 用 `block.getContent()`（入参的 JSON 串）做 schema 校验，只设 `input` 会报 `argument "content" is null` → 工具 ERROR → 模型重试循环、回复恒空。`ChatController.confirm` 三处重建 block 均已补 `content`；审批接口返回 `toolInput`（JSON **字符串**），前端回传时必须 `JSON.parse` 后放进 `input`。
- **SSE 事件载荷已补全**：`buildEventJson` 对 Thinking/Text 增量（`delta/blockId/replyId`）、工具生命周期（`toolCallId/toolCallName`，`TOOL_RESULT_END` 带 `state`）、`ExceedMaxIters`/`AllToolsDenied`/`Hint`/`Custom`/`ModelCallEnd(usage)` 等输出完整字段，前端可按 `replyId+blockId/toolCallId` 重建消息；流式文本累积读 `TEXT_BLOCK_DELTA` 的 `delta` 字段。
- **权限规则匹配（易踩坑）**：AgentScope `ToolBase.matchRule` 对非文件类工具**仅当 `ruleContent == null` 时返回 true**，`"*"` 不是通配符。库中沿用 `"*"` 存储，`GovernanceService.buildPermissionContext` 负责映射为 `null`（`normalizeRuleContent`）——**不要**把 `"*"` 直接传进 `PermissionRule`，否则 ALLOW/ASK/DENY 全部失效，工具一律落到 mode 默认（DEFAULT=每次询问，DONT_ASK=一律拒绝），表现为「空回复 / 工具被拒」。另：同一工具的同内容行为唯一（`upsertRule` 会删掉其它 behavior），因为引擎按 `DENY→ASK→ALLOW` 判定，并存的 ASK 会盖掉 ALLOW。
- **权限/模式变更即时生效**：规则/mode 变更走三步缺一不可——①`upsertRule`/`setMode` 落库；②`evictTenant` 驱逐实例（权限上下文只在 build 时注入）；③**`SessionStateRuleScrubber.resyncTool/resyncMode` 重同步 Redis 会话状态**。引擎实际判定优先读 `agent_state.permission_context`，状态里既有 ask/mode 不会被重建覆盖且 builder 规则只做合并不替换，漏掉③则旧会话按旧规则判定（实测：落库+驱逐后仍 409）。管理台 `AdminGovernanceController` 与 confirm 均已接。
- **ASK 建议规则（一键免问）**：`GET .../approvals` 每项带 `suggestedRules`（工具 `generateSuggestions` 生成，空则回退 tool 级 `ruleContent=null ALLOW`）；`confirm` 请求 `toolCalls[].acceptRule=true`（或顶层 `acceptRule`）时，规则随 `ConfirmResult` 回传引擎 **并** 平台落 `permission_rule`（source=suggested/session，upsertRule 替换同工具 ASK）+ 驱逐 + 状态重同步 → 同工具后续调用（含同会话与其它既有会话）免问。范围为**租户级**（管理台可见可改）；Java 2.0.3 的 `ConfirmResult.rules` 单独使用不会写入状态（实证），故平台落库是生效主路径。
- **OpenAI 兼容端点的 HITL**：`POST /v1/chat/completions`（含 stream）暂停时返回 **409 + OpenAI error 结构**（`code=HITL_CONFIRM_REQUIRED`），审批同样经 `ApprovalInbox` 落库；恢复用平台 `POST .../confirm`（OpenAI 协议本身无确认语义）。流式暂停结束发 error chunk 而非空 stop。
- **GenerateReason 透出**：非流式响应体带 `generateReason` 字段（`MODEL_STOP`/`PERMISSION_ASKING`/`ALL_TOOLS_DENIED`/`INTERRUPTED` 等，取自最终 Msg）；409 `HITL_CONFIRM_REQUIRED` 的 message 内也带 `(generateReason=...)`。
- **清会话上下文**：`POST /api/v1/agents/{agentId}/sessions/{sessionId}/clear-context`（body `userId`）→ `CONTEXT_CLEARED`；调 harness `clearContext(ctx)` 重置该会话槽位（对话清空、权限回初始规则）并持久化 stateStore，前端「清上下文」按钮同路径。
- **全拒停止**：`StopOnAllDeniedMiddleware`（官方配方）挂在 builder——用户拒绝全部工具后本轮立即以 `generateReason=ALL_TOOLS_DENIED` 结束，不再空转下一轮。
- **智能体不走 Java 种子**：`CatalogBootstrap` 已去掉 demo/console-bot/biz-assist 硬编码；新增用 `POST /api/v1/admin/agents` + versions + publish，或 `scripts/provision_agents.py` / `db/seed-agents.sql`。历史坑：Java 种子曾按 tool+behavior 判存在并在重启时覆盖用户 acceptRule 规则（upsertRule 替换语义），该逻辑已随种子删除。
- **实例构建租户以 Key 为准**：`AgentInstanceCreator.create(tenantId, ...)` 接收请求租户，权限规则 / MCP / 技能 / 知识 / 沙箱 / 模型凭证均按该租户解析（`resolveTenant()` 仅作非请求上下文回退）。
- **workspace 路径含租户**：version.workspace 为空**或等于裸基础路径**（旧数据行）时，运行时组合 `{AMP_WORKSPACE}/{tenantId}/v{version}`；自定义路径原样保留。新建版本请留空 workspace。
- **AgentScope 运行时配置**（`amp.agent.*`）：`compaction-trigger-messages`（默认 30）/ `compaction-keep-messages`（默认 10）——**显式**配置压缩（Java 官方 quickstart 推荐；2.0.3 字节码默认开，但官方文档口径为按需开启，显式化防版本漂移），构建日志打印 `compaction config ...`；`compaction-trigger-tokens`（0=按模型窗口动态触发，>0 覆盖为固定阈值）、`tool-result-eviction`（默认 true，80K 字符逐出）、`fallback-model-id`（空=不回退，设置后 build 日志有 `fallback model enabled`）、`max-retries`（默认 3）、`max-context-tokens`（默认 8000，**workspace 上下文注入预算**，与会话压缩窗口无关）。官方文档入口为 **https://java.agentscope.io/v2/zh/docs/quickstart**（勿用 Python 版 docs.agentscope.io）。
- **S4 工具**：进程内 `SampleToolset`（`safe_lookup`/`dangerous_write`，通用示例非业务域）；MCP 登记表 + ToolsConfig 注入（连真实 Server 属债务）。
- **HTTP 通用工具**：`business_tool.kind=HTTP` + `config{url,method,headers,parameters}` → `HttpBusinessTool`（`ToolBase`）按登记自动注册到 Toolkit，模型可直接调用；`IN_PROCESS` 仍仅目录元数据。
- 治理 Admin：`/api/v1/admin/governance/permissions`、`/mcp-servers`、`/business-tools`、`/inbox`、`/approvals/{id}/decide`；会话侧 `GET .../approvals`。
- **工具生产化（P0–P2）**：`business_tool` 扩列（version/env/owner/secret_ref/timeout_ms/max_retries/tags 等）+ `agent_tool_binding` + `tool_credential`。生命周期 `DRAFT|ENABLED|DISABLED|DEPRECATED`；`POST .../business-tools/{name}/lifecycle|test|clone`；软删=DEPRECATED（`?hard=true` 硬删）。**装配**：`mountableTools`——有绑定行只挂绑定启用的工具，无绑定兼容挂全部 ENABLED。**HttpBusinessTool**：超时/只读重试/熔断/脱敏/`secret:` 引用；config 禁止明文 Authorization（须 `secretRef`）。SSRF：`amp.tools.block-private-urls=true` 时拦内网 URL。控制台「工具中心」：目录总览、详情抽屉（配置/装配/审计/试调用）。
- **S5 技能市场**：`skill`/`skill_review` 表；`MarketplaceSkillRepository` **只读且仅 APPROVED**；上传=DRAFT，approve/reject 写审核记录并 **evict 实例缓存**（审后下轮生效）；`setWriteable` 被锁死 false。
- 技能 Admin：`/api/v1/admin/skills`（GET/POST）、`/{id}/approve|reject|archive`、`/visible`。
- **S6 RAG**：`knowledge_document`/`knowledge_chunk`；`LocalHashEmbedder` 自建向量；工具 `knowledge_search` 返回 `[docId#chunkId]` 引用；Admin `/api/v1/admin/knowledge/documents|search`。
- **S6 RAG Provider**：`KnowledgeService` SPI；`amp.rag.dify.enabled=true` 时走 Dify Dataset API（`DifyKnowledgeClient`，`AMP_DIFY_KB_*` / `amp.rag.dify.*`），否则 Simple+PG；`GET /api/v1/admin/knowledge/status` 返回 provider。
- **S7 观测**：`RateLimitFilter`（默认 120/min，可热更）；Micrometer/Prometheus `/actuator/prometheus`；`PlatformMetrics`（chat/tokens/tool/hitl）；`chat.done` 日志含 sessionId；配置 `/api/v1/admin/config`。
- **S8 多副本**：`/api/v1/health/ready`（Redis+PG）；`AMP_REPLICA_ID`；External `external_agent` 注册/心跳/超时 unhealthy；双副本脚本 `run-api-18081/18082.cmd`。**杀一副本会话不丢**（State 在 Redis）。
- **SSE 续传**：帧 `id={sessionId}:{seq}`；`Last-Event-ID` 短窗重放（默认 30s）；过期 `SSE_RESUME_MISS` → history 兜底。
- **会话只读事件流**：`GET /api/v1/agents/{agentId}/sessions/{sessionId}/stream?userId=`（SSE，open scope）——不触发运行，先回放缓冲再实时接收；与 `POST .../messages:stream` 共享同一 `SseReplayBuffer` 帧编号（`SessionEventHub` 扇出），`Last-Event-ID` 两端通用。**非流式回合（messages/confirm）的事件也会扇出**到该流；多 tab 同步、唤醒结果观察均走它。
- **interrupt**：`POST .../sessions/{sid}/interrupt`（`userId` body）→ `HarnessAgent.interrupt`；实测调用后 ~2.7s 内停流，会话可续聊。
- **AgentScope 2.0 能力开关**（`amp.agent.enable-*`，默认关，开后 build 日志有 `agentscope 2.0 features` 行、工具清单变化）：`enable-plan-mode`（plan_enter/exit/write）、`enable-task-list`（todo_write）、`enable-meta-tool`（reset_equipped_tools）。子智能体开关沿用 `amp.tools.enable-agent-spawn`。
- **异步工具卸载（默认关，有已知限制）**：`amp.agent.async-tool-timeout-ms>0` 装 `AsyncToolMiddleware`（超时转后台 + 占位返回 + 完成入 inbox + 唤醒）。平台侧链路已通：共享 `MessageBus` bean（workspace 文件实现）+ `AgentWakeupService`（`WakeupDispatcher` 订阅，`streamEvents(List.of(), ctx)` 空消息恢复，事件扇出到会话流，日志 `wakeup.done`/`wakeup run failed`）。**已知限制（2.0.3+DeepSeek）**：工具完成快于下一轮模型调用时，完成注入会在占位符后追加**同 id 第二条 tool_result**，DeepSeek 严格配对校验 400（`role 'tool' must be a response to preceding tool_calls`），会话上下文被污染；实测 1ms 阈值必现，故默认 0 关闭。待上游修复或换宽松 provider 后再启用。
- **External Chat**：托管无此 agent → 转发 `{baseUrl}/api/v1/messages`（`ExternalChatClient`）；响应 `reply` 字段（兼容 text/message）。
- **S9 External 流式**：托管无此 agent 时透传 **`POST {baseUrl}/api/v1/messages:stream`**（请求体同 messages：`agentId/tenantId/userId/sessionId/message`，返回 `text/event-stream`）。平台逐行透传 SSE 帧；注册 `callbackKey` 时带 `X-Callback-Key`。失败 `EXTERNAL_CHAT_FAILED`。演示：`scripts/external-sse-mock.py` + `scripts/demo-external-stream.ps1`。
- **S9 计费配额**：表 `tenant_quota`（`monthly_token_limit` / `monthly_request_limit`，0=不限）+ `usage_ledger`（prompt/completion tokens、`cost_micros`、source）。对话前 `QuotaService.check`，成功后 `record`；超额 `QUOTA_EXCEEDED`。Admin（**admin scope**）：`GET/POST /api/v1/admin/quotas`、`GET /api/v1/admin/usage?limit=`、`GET /api/v1/admin/usage/report?year=&month=`、`GET /api/v1/admin/usage/export?from=&to=`（CSV）。
- **二期 S9 编排**：表 `workflow_def`/`workflow_revision`/`workflow_run`/`workflow_step_run`/`team`/`team_member`；`LocalWorkflowRunner` 步骤 `ECHO|AGENT|HUMAN_GATE`（P2-B1 降级路径，Service 对接预留 `WorkflowAgentInvoker`）；API `PUT /api/v1/workflows/{id}`、`POST .../revisions`、`POST .../runs`、`GET /runs/{id}`、`POST /runs/{id}/confirm|cancel`；Team ` /api/v1/teams`。
- **二期 Channel**：`ChannelAdapter` SPI + `MockChannelAdapter`；`POST /api/v1/channels/{type}/webhook`（`X-Channel-Token` 校验，**不走 API Key**）。
- **二期 RBAC / IAM-RBAC**：`IamService`/`RbacGuard`；权限点 `orch:read|write|run`、`iam:admin`、`billing:read|write`、`skill:review`、`agent:call` 等。**双轨身份**：人轨 JWT（`spring-boot-starter-oauth2-resource-server`，`amp.iam.issuer-uri`/`jwk-set-uri`，空=仅 API Key 兼容模式）优先于机器轨 API Key；claims `sub`→userId、`tenant`/`tenantId`/`azp`→tenantId，`roles`/`realm_access.roles`/`resource_access.*.roles`→平台角色。**`X-User-Id`/`X-User-Roles` 默认不信任**（`amp.iam.trust-headers` 默认 false，迁移窗口才开）；admin scope 的 Key 直接放行，JWT 人身份走 RBAC（`tenant_admin`/`admin` 角色过 `requireAdmin`）。未知 Bearer 在 JWT 启用时交给 Resource Server（`ApiKeyAwareBearerTokenResolver` 跳过已知 Key）。Admin `/api/v1/admin/iam/**`。**前端 OIDC**：`platform-console-ui/src/auth/oidc.js`（Authorization Code + PKCE），`http.js` JWT 优先 Bearer、未登录回退 API Key；回调 `/auth/callback`；发现 `GET /api/v1/health/iam`（免鉴权）。Keycloak 开发 compose：`docker-compose.keycloak.yml`。方案见 `docs/IAM-RBAC方案.md`；开发文档见 `docs/二期项目开发文档.md`。
- **二期 S10**：`RuntimeWorkflowAgentInvoker`（`@Primary`）接 Harness/External 真实对话 + 配额/指标；Echo 仅兜底。管理台新增「编排 Workflow」页（Workflow/Run/Team/IAM）。
- **Mem0 长期记忆**（官方 `agentscope-extensions-mem0`）：依赖 `agentscope-extensions-mem0:2.0.3`。`ScopedMem0LongTermMemory`（`LongTermMemory` SPI）按会话作用域委托官方 `Mem0LongTermMemory`（agentName=agentId / userId=`{tenant}:{user}` / runName=sessionId + metadata）。工具面官方 `LongTermMemoryTools`：`recordToMemory` / `retrieveFromMemory`。`Mem0MemoryMiddleware`（MiddlewareBase，order=50）对话前语义召回注入 `<long_term_memory>`、对话后 record 抽取沉淀；`MemoryScope` ThreadLocal 供工具解析身份。配置 `amp.mem0.*`（enabled/base-url/api-key/api-type=self-hosted|platform/auto-recall/auto-capture）。**Docker**：`docker compose -f docker-compose.mem0.yml --env-file scripts/mem0/.env up -d`（API :8888，PG :15433；Dashboard `--profile dashboard`）。Admin：`GET/POST /api/v1/admin/memories`（status/search/list/get/put/delete，admin scope，`Mem0AdminClient`）。文档入口 **https://java.agentscope.io/v2/zh/integration/memory/mem0**。
- **控制台智能机器人（console-bot）**：右下角浮动「小助」，**平台最佳实践智能体**，目录/工具/知识/权限均可在管理台治理。不再由 CatalogBootstrap 播种；用 `scripts/provision_agents.py` 经管理 API 创建：Agent `console-bot` + v1 系统提示词 + 发布指针；`business_tool` 登记内置工具（`config.owner=console-bot`，IN_PROCESS）；权限 ALLOW/ASK；知识《控制台智能机器人使用指南》《AI中台能力地图》；技能草稿 `console-bot-best-practice`。工具面 `ConsoleBotToolset`：`platform_status` / `list_agents` / `agent_detail` / `list_permissions` / `list_business_tools` / `list_skills` / `list_knowledge` / `console_knowledge_search` / `list_workflows` / `list_teams` / `usage_summary` / `list_audit` + `ui_navigate` / `ui_focus` / `ui_notify` / `ui_ask_user` / `ui_suggest`（结果带 `ui.actions`）。前端 `ConsoleBot.vue` + `botBus.js`；PageHeader「问小助」。`ConsoleBotSupport` 聚合服务。
- **OpenAI 兼容**：`POST /api/v1/chat/completions` 与 `/v1/chat/completions`（`OpenAiCompatController`，open scope Bearer）；`model` 映射 agentId，支持 `stream` SSE chunk + `[DONE]`。
- **Nacos（可选）**：`amp.nacos.enabled` 默认 false；`NacosConfigBridge` 拉取/监听 `amp.ops.*` 热更；`GET /api/v1/admin/nacos` 查源；失败回退 local。`nacos-client` **3.2.3**。
- **Nacos AI Registry（可选，决策已锁）**：`amp.nacos.ai.enabled` 默认 false；**仅 nacos→local**（`mcp_server.source`，local 不被覆盖）；**AgentCard 仅可发现**；namespace **shared=public** / identity / **prefix**（`namespace-prefix` 默认 `amp-`）。Admin：`GET .../nacos/ai|mcp|agents|prompts|skills`、`POST .../mcp/sync|agents/import|skills/import|prompts/refresh`。`NacosAiAdminClient` + `NacosAiSyncService` + `NacosPromptService`。**Skill**：Nacos 导入为 **DRAFT `source=nacos`**，须 `approve` 后进 `MarketplaceSkillRepository`（不绕过审核）；Skill/Prompt API 需 **Nacos ≥3.2**（3.1 仅有 MCP/A2A）。**Prompt**：键 `amp.agent.{id}.sys`，pin `v{N}`/`stable` 或 `latest`。`sync-on-start` 可选。冒烟 `scripts/nacos-ai-smoke.ps1`。
- **OTel**：`OtelBridge`（`amp.otel.*` / `OTEL_EXPORTER_OTLP_ENDPOINT`）；无 endpoint 用 logging 导出；`OtelTracingMiddleware` 挂每个 HarnessAgent；`GET /api/v1/admin/otel`。
- **Loki**：`amp.loki.*` 可选 push/query；`SessionLogService` 写 sessionEvent 并按 `sessionId=` 检索（本地文件兜底）；`GET /api/v1/admin/loki/search`。
- **MCP 真实连通**：`McpConnectService`（initialize+listTools/callTool）；登记后注入 `ToolsConfig.mcpServers`（短超时软失败）；`POST /api/v1/admin/mcp-servers/{name}/test`；演示 `scripts/mcp-demo-server.py`。**enableTools 为空默认不注入**（`amp.mcp.require-explicit-tools=true`）。
- **Docker 沙箱**：`amp.sandbox.*` 默认关；`DockerSandboxService`（network=none/read-only/cap-drop/降权）；工具 `sandbox_exec`；Admin `/api/v1/admin/sandbox`。
- **Remote FS**：`PgBaseStore` + `RemoteFilesystemSpec` + `DistributedStore`（State=Redis，Base=PG）；`amp.fs.mode=remote|local`；Admin `/api/v1/admin/fs`。
- 错误体：`{"code":"..."}`（如 `RATE_LIMITED` / `QUOTA_EXCEEDED` / `EXTERNAL_UNHEALTHY` / `SSE_RESUME_MISS` / `EXTERNAL_CHAT_FAILED` / `MCP_NOT_FOUND` / `SANDBOX_DISABLED` / `INSUFFICIENT_SCOPE`）。

## 代码边界

- 后端包根：`com.sinosoft.amp.openapi`（包名跨模块不变）；公共类型在 `com.sinosoft.amp.common`。
- **Maven 分层（勿逆向依赖）**：`common → runtime → governance → ops → channel → open-api`；装配类（`AgentInstanceFactory` / `CatalogBootstrap`（仅运维启动，无目录种子） / `AgentRuntimeConfiguration` / `ConsoleBot*`）在 `platform-open-api`。
- 目录 SQL：`platform-open-api/src/main/resources/db/schema.sql`（PG `ON CONFLICT` upsert）。
- 单测覆盖 cache/drain/publish/治理/技能/配额（内存目录 + mock），不启 Spring 上下文；**208 测分布在各模块**。
- 前端在 `platform-console-ui/src`（路由 + views/components/api）；改契约时后端与 `api.js` 需同步。
- **本地启动**：`C:\amp` junction；先 `mvn -DskipTests install`，再 `scripts/run-api-18081.cmd` + 计划任务 `amp-open-api-18081`（避开中文路径编码问题）。
- **UI 联调脚本**：`scripts/ui-click-test.mjs`（路由/侧栏）、`scripts/ui-interact-test.mjs`（对话发送）；在 `platform-console-ui` 下用本地 `playwright` 运行。
