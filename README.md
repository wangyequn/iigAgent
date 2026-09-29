# AI 中台 · S0–S9

基于 **AgentScope Java 2.0.3** 的 AI 中台 Walking Skeleton（**S0–S9 全部落地**）。

设计文档见 [`docs/AI中台设计文档.md`](docs/AI中台设计文档.md)。

## 环境要求

- **JDK 21**（`pom.xml` 要求 release=21）、Maven 3.8+
- Docker（Compose 起 Redis + PostgreSQL + 应用）
- 模型密钥：`DEEPSEEK_API_KEY`（默认）或 `DASHSCOPE_API_KEY` / `OPENAI_API_KEY`（按 `AMP_MODEL_ID` 选择）
- 默认端口 **18081**（可用 `AMP_SERVER_PORT` 覆盖）

## 10 分钟跑通

> 需已启动 **Docker Desktop**。国内镜像：
> `docker pull docker.m.daocloud.io/library/redis:7-alpine` → `docker tag ... redis:7-alpine`
> `docker pull docker.m.daocloud.io/library/postgres:16-alpine` → `docker tag ... postgres:16-alpine`

```powershell
# 1) 优先：本机已有 Redis:6379 时只起 PostgreSQL（宿主机 15432）
& "C:\Program Files\Docker\Docker\resources\bin\docker.exe" run -d --name amp-pg -p 15432:5432 `
  -e POSTGRES_DB=amp -e POSTGRES_USER=amp -e POSTGRES_PASSWORD=amp postgres:16-alpine
# 或 compose：up -d postgres
# 若本机无 Redis，再起：amp-redis 映射 16379:6379，并设 AMP_REDIS_URI=redis://127.0.0.1:16379

# 2) 设置模型（默认 DeepSeek）
$env:DEEPSEEK_API_KEY = "sk-..."

# 3) 构建 + 测试
mvn -B verify

# 4) 启动（默认 http://127.0.0.1:18081）
mvn -pl platform-open-api -am spring-boot:run
```

> **S3 起** 目录/版本表在 **PostgreSQL**（`agent` / `agent_version` / `endpoint_release`），State 仍在 Redis。缺 PG 时应用无法启动。

### API Key（S1 + scopes）

除 `/api/v1/health/**` 外均需：

```text
Authorization: Bearer <api-key>
```

| Key | scopes | 用途 | 说明 |
| --- | --- | --- | --- |
| `s0-demo-key`（`AMP_API_KEY`） | `open` | 业务对话 / 会话五接口 | **不能**调 `/api/v1/admin/**` |
| `s0-admin-key`（`AMP_ADMIN_API_KEY`） | `open, admin` | 管理台 / 运维 / 审计 / 配额 | 生产请独立轮换 |

租户默认 `acme`，可访问 agent `demo`（`application.yml` → `amp.auth`）。  
**tenantId 以 Key 为准**，请求体可不传或必须与 Key 一致。  
Open Key 调 admin → `403 INSUFFICIENT_SCOPE`。

### 其他应用接入

| 接入方式 | 端点 | 鉴权 | 状态 |
| --- | --- | --- | --- |
| Open Key 对话 | `POST /api/v1/agents/{agentId}/messages`、`POST .../messages:stream` | `Bearer s0-demo-key`（scope `open`） | **已完成** |
| 会话五接口 | `messages` / `messages:stream` / `confirm` / `interrupt` / `history` | 同上 | **已完成** |
| Admin Key 管理 | `GET/POST /api/v1/admin/**`（目录、技能、治理、审计、配额…） | `Bearer s0-admin-key`（scope `open, admin`） | **已完成** |
| OpenAI 兼容 `chat/completions` | `POST /api/v1/chat/completions`、`POST /v1/chat/completions`（`OpenAiCompatController`） | **已实现** | `model`=agentId，支持 `stream` SSE |
| External Agent 转发 | 托管无此 agent → `{baseUrl}/api/v1/messages` / `messages:stream` | 注册时 `callbackKey`（`X-Callback-Key`） | **已完成**（S8/S9） |

### 审计

管理面写操作 / HITL confirm / interrupt 自动写入 `audit_log`。  
查询：`GET /api/v1/admin/audit?limit=50`（需 admin scope）。

### 模型凭证（最小闭环）

- 表 `model_credential`（tenant + provider 唯一）；接口返回 **打码**（`****xxxx`）
- Admin：`GET/POST /api/v1/admin/model-credentials`、`DELETE .../{provider}`
- 构建实例时 `ModelCreationContext.apiKey/baseUrl` **库内优先**，环境变量回退
- `modelId` 形如 `deepseek:deepseek-flash` → provider=`deepseek`

```powershell
$headers = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/model-credentials -Headers $headers `
  -d '{"provider":"deepseek","apiKey":"sk-..."}'
curl -s http://127.0.0.1:18081/api/v1/admin/model-credentials -Headers $headers
```

### 工具面默认收紧

| 配置（`amp.tools.*`） | 默认 | 说明 |
| --- | --- | --- |
| `mode` | `deny` | `allow` = 白名单（生产推荐） |
| `allow` | `query_orders,place_order,knowledge_search` | allow 模式生效 |
| `enable-agent-spawn` | false | 关闭 subagent / `agent_spawn` |
| `enable-shell` | false | 关闭 shell/execute |
| `enable-filesystem` | false | 关闭读写/检索文件 |
| `extra-deny` | `[]` | 追加黑名单 |

env：`AMP_TOOLS_MODE` / `AMP_TOOLS_AGENT_SPAWN` / `AMP_TOOLS_SHELL` / `AMP_TOOLS_FS`。

### RAG Embedding（可插拔）

| `amp.rag.provider` | 说明 |
| --- | --- |
| `hash`（默认） | 本地 n-gram 哈希 128 维，零依赖 |
| `http` | OpenAI 兼容 `POST {base}/v1/embeddings`，**失败自动回退 hash** |

```yaml
amp.rag:
  provider: http
  http-base-url: https://api.openai.com   # 或 DashScope 兼容网关
  http-api-key: ${AMP_RAG_API_KEY}
  http-model: text-embedding-3-small
  http-dimensions: 1536   # 可选
```

env：`AMP_RAG_PROVIDER` / `AMP_RAG_BASE_URL` / `AMP_RAG_API_KEY` / `AMP_RAG_MODEL` / `AMP_RAG_DIMS`。  
存储仍为 JSON 向量（变长）；**pgvector ANN** 仍属债务（检索现为余弦全表）。

### 技能灰度（CanaryFilter）

| 配置 | 说明 |
| --- | --- |
| `amp.skill.canary.environment` | 当前环境名（dev/staging/prod） |
| `amp.skill.canary.default-percent` | 默认放量 0–100 |
| `amp.skill.canary.percents.<name>` | 按技能名覆盖 |
| `amp.skill.canary.allowed-environments` | 默认允许的环境列表 |

技能 metadata：`rolloutPercent`、`environments`（`skill` 表列 `rollout_percent` / `visibility_env`）。  
哈希：`userId|skillName` 稳定映射到 0–99，保证同一用户结果稳定。

### curl 演示

```powershell
# Open Key（业务对话）
$H = @{ Authorization = "Bearer s0-demo-key"; "Content-Type" = "application/json" }
# Admin Key（管理面）
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }

# 非流式
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/demo/messages -Headers $H `
  -d '{"userId":"u1","sessionId":"agt_demo_s0a","message":"我叫天宇，请记住我"}'

# 同 sessionId 第二轮
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/demo/messages -Headers $H `
  -d '{"userId":"u1","sessionId":"agt_demo_s0a","message":"我叫什么？"}'

# 流式 SSE
curl -N -X POST http://127.0.0.1:18081/api/v1/agents/demo/messages:stream -Headers $H `
  -d '{"userId":"u1","sessionId":"agt_demo_s0b","message":"用一句话介绍你自己"}'

# history（S1）
curl -s "http://127.0.0.1:18081/api/v1/agents/demo/sessions/agt_demo_s0a/history?userId=u1" -Headers $H

# interrupt（S1）
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/demo/sessions/agt_demo_s0a/interrupt -Headers $H `
  -d '{"userId":"u1"}'

# confirm（S1，HITL 恢复骨架）
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/demo/sessions/agt_demo_s0a/confirm -Headers $H `
  -d '{"userId":"u1","toolCalls":[{"toolCallId":"c1","toolName":"demo","confirmed":true}],"note":"ok"}'

# 无 Key → 401；缺 sessionId → 400 SESSION_CONTEXT_INVALID
# Open Key 调 admin → 403 INSUFFICIENT_SCOPE
```

### 全容器 Compose

```powershell
$env:DEEPSEEK_API_KEY = "sk-..."
docker compose up --build
# 宿主机：http://127.0.0.1:18081
```

## 模块

| 模块 | 职责 |
| --- | --- |
| `platform-common` | 错误码等公共 DTO |
| `platform-open-api` | Spring MVC + 目录/版本 + 实例缓存 + SSE |
| `platform-console-ui` | Vue3 Playground（S3：版本列表 / 发布切流 / 对话） |

## S3 目录与版本（B2 最小实现）

表结构：`platform-open-api/src/main/resources/db/schema.sql`  
运行时：`AgentInstanceCache`（懒加载 + drain）· `CatalogRuntimeService`（发布/切流）· `AdminCatalogController`

```powershell
$H = @{ Authorization = "Bearer s0-demo-key"; "Content-Type" = "application/json" }
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }

# 查看版本与当前 release
curl -s "http://127.0.0.1:18081/api/v1/agents/demo/versions" -Headers $H

# 建 draft v2（可改 sysPrompt / modelId）— 需 admin
curl -s -X POST "http://127.0.0.1:18081/api/v1/admin/agents/demo/versions" -Headers $HA `
  -d '{"modelId":"deepseek:deepseek-flash","sysPrompt":"你是 v2 演示助手"}'

# 发布并切流（build 成功才切指针；旧版 drain 日志可见）
curl -s -X POST "http://127.0.0.1:18081/api/v1/admin/agents/demo/versions/2/publish" -Headers $HA

# 实例缓存快照
curl -s "http://127.0.0.1:18081/api/v1/admin/runtime/instances" -Headers $HA
```

日志关键字：`endpoint switch`、`drain start`、`drain done`（in-flight 归零或超时默认 120s 后 evict）。  
Playground：Agent 下拉显示 release 版本；「发布并切流」一键建版+publish。

目录 env：`AMP_PG_URI` / `AMP_PG_USER` / `AMP_PG_PASSWORD`（默认 `jdbc:postgresql://127.0.0.1:15432/amp`，`amp`/`amp`）。

## S2 Playground

```powershell
# 后端保持运行（18081）后：
cd platform-console-ui
npm install
npm run dev
# 浏览器打开 http://127.0.0.1:5173
# 默认 API Key: s0-demo-key，Agent: demo，走 Vite 代理到 18081
```

页面能力：Agent 列表（含 release 版本）、流式对话、SSE 事件面板、history / interrupt、**发布新版本并切流**。

## 验收

### S0
- [x] Compose 配置就绪
- [x] `messages` / `messages:stream`
- [x] sessionId 强制校验
- [x] Redis AgentState 装配
- [x] 单测 + `mvn verify` + CI

### S1
- [x] API Key 过滤器（401 / 租户绑定 / agent 白名单）
- [x] 五接口：messages / stream / confirm / interrupt / history
- [x] tenantId 以 Key 为准（防伪造）
- [x] history 经 `getDelegate().getAgentState` 读 Redis
- [x] `mvn verify` 全绿

### S2
- [x] `GET /api/v1/agents`（按 Key 返回可访问 Agent，配置写死）
- [x] Vue3 Playground：流式对话 + 事件面板 + history/interrupt
- [x] `platform-console-ui` build 通过（dist/）
- [x] 后端 18081 + Vite 5173 在线；代理 `/api` → agents/stream 200
- [x] 浏览器打开 `http://127.0.0.1:5173` 使用

### S3
- [x] `agent` / `agent_version` / `endpoint_release` 表（PG + schema.sql）
- [x] 懒加载实例缓存 + 发布切流（B2）
- [x] 旧版 drain 日志 + 定时 sweep
- [x] Admin API：建 Agent/版本、publish、实例快照
- [x] Playground：版本列表 / 一键发布切流
- [x] `mvn verify` 全绿

### S4（HITL + 工具）
- [x] 权限表：`permission_rule` / `agent_permission_mode`（ALLOW/ASK/DENY）
- [x] MCP 登记：`mcp_server` + Admin API（真实连通属债务）
- [x] 业务工具：`OrderToolset`（`@Tool`）`query_orders` / `place_order`
- [x] `approval_item` + SSE `REQUIRE_USER_CONFIRM` + confirm 恢复
- [x] 幂等：重复 confirm → `409 APPROVAL_ALREADY_RESOLVED`
- [x] Admin：permissions / mcp-servers / business-tools / inbox / decide
- [x] 会话：`GET .../approvals`；`POST .../confirm`
- [x] Playground：待确认「批准/拒绝」
- [x] `mvn test` 全绿（24）

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
# 权限
curl -s http://127.0.0.1:18081/api/v1/admin/governance/permissions -Headers $HA

# SSE 下单 → REQUIRE_USER_CONFIRM → place_order pending
# POST /api/v1/agents/demo/sessions/{sid}/confirm
#   -d '{"userId":"u1","toolCalls":[{"toolCallId":"...","toolName":"place_order","confirmed":true,"input":{"product":"书","quantity":"1"}}],"confirmed":true}'
```

### S5（技能市场）
- [x] `skill` / `skill_review` 表
- [x] `MarketplaceSkillRepository`（`writeable=false`，仅 **APPROVED** 可见）
- [x] 管理台：上传草稿 / 审核通过 / 驳回 / 归档
- [x] 审核后驱逐实例缓存 → **审后下轮生效**
- [x] 种子草稿 `order-style`（bootstrap）
- [x] `mvn test` 全绿（29）
- [x] Playground 技能市场面板

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
curl -s http://127.0.0.1:18081/api/v1/admin/skills -Headers $HA
curl -s http://127.0.0.1:18081/api/v1/admin/skills/visible -Headers $HA
# 上传草稿 → 未审不可见
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/skills -Headers $HA `
  -d '{"name":"hello-skill","version":"1.0.0","description":"demo","skillContent":"# hello"}'
# 审核
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/skills/{id}/approve -Headers $HA -d '{"note":"ok"}'
```

### S6（Simple RAG）
- [x] `knowledge_document` / `knowledge_chunk` 表
- [x] 自建 `LocalHashEmbedder`（128 维 n-gram 哈希，无外部服务）
- [x] 入库切片 + 余弦检索 + **引用片段 `[docId#chunkId]`**
- [x] 工具 `knowledge_search`；Admin：`/api/v1/admin/knowledge/documents`、`/search`
- [x] 种子文档《AI中台订单规范》
- [x] `mvn test` 全绿（33）

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/knowledge/documents -Headers $HA `
  -d '{"title":"退货政策","content":"七天无理由退货。"}'
curl -s "http://127.0.0.1:18081/api/v1/admin/knowledge/search?query=place_order&topK=3" -Headers $HA
# 响应 hits[].citation 如 [docId#docId_0]，text 为原文片段
```

> 说明：一期 Simple 用自建 embedding 落库（兼容 H2 单测）；生产可换模型 embedding / 真 pgvector ANN。

### S7（配置与观测）
- [x] 限流中间件：固定窗口，按 API Key/IP，默认 120/min，`429 RATE_LIMITED`
- [x] 运行时配置热更：`GET/POST /api/v1/admin/config`（进程内；Nacos 完整对接属债务）
- [x] 指标：`amp.chat.*` / `amp.tokens.*` / `amp.tool.calls` / `amp.hitl.*` + P50/P95/P99
- [x] Prometheus：`/actuator/prometheus`；摘要：`/api/v1/admin/metrics/summary`
- [x] 会话可检索日志：`chat.done` / `stream.done` 含 `sessionId`/`userId`/`durationMs`
- [x] Token 用量粗估 + 计费雏形字段
- [x] `mvn test` 全绿（37）

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
# QPS/延迟/Token 摘要
curl -s http://127.0.0.1:18081/api/v1/admin/metrics/summary -Headers $HA
# Prometheus 原文（Grafana 可直接抓）
curl -s http://127.0.0.1:18081/actuator/prometheus | Select-String amp_
# 热更限流
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/config -Headers $HA -d '{"rateLimitPerMinute":30}'
# 日志查会话（本机 logs/amp-open-api-18081.log）
Select-String -Path logs\amp-open-api-18081.log -Pattern 'sessionId=agt_ui'
```

### S8（多副本 + External 最小集）
- [x] 就绪探针 `GET /api/v1/health/ready`（Redis + PG）
- [x] 副本标识 `replica` 字段 / `AMP_REPLICA_ID`
- [x] 启动校验日志 `STARTUP_CHECK_OK/FAIL`
- [x] External 最小集：`external_agent` 表 + 注册 / 心跳 / 超时 unhealthy
- [x] 双副本演练：**杀一副本会话不丢**（A 对话 → 杀 A → B 同 sessionId 续聊记得「天宇」）
- [x] `mvn test` 全绿（40）

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
# 双副本
# scripts/run-api-18081.cmd (replica-a) + scripts/run-api-18082.cmd (replica-b)
curl -s http://127.0.0.1:18081/api/v1/health/ready
curl -s http://127.0.0.1:18082/api/v1/health/ready
# External
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/external-agents -Headers $HA `
  -d '{"name":"ext1","baseUrl":"http://127.0.0.1:19099","capabilities":"chat"}'
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/external-agents/{id}/heartbeat -Headers $HA
```

## N4 上线检查单（S8）

- [ ] JDK 21 / Maven 3.8+；`mvn -B verify` 绿
- [ ] Redis **分布式** StateStore（禁用本地 FS State 上生产）
- [ ] PostgreSQL 可用；`db/schema.sql` 已初始化
- [ ] `DEEPSEEK_API_KEY`（或对应模型 Key）已注入，禁止写死进仓库
- [ ] 至少 **2 副本**；LB 使用 `/api/v1/health/ready` 做就绪探针
- [ ] 会话续聊抽检：同 `sessionId` 跨副本 history/call 可用
- [ ] 限流已按容量调整（`amp.ratelimit.rate-limit-per-minute`）
- [ ] `/actuator/prometheus` 已接入监控；日志可按 `sessionId=` 检索
- [ ] 技能市场：未审技能不可见（回归 `/api/v1/admin/skills/visible`）
- [ ] HITL：`place_order` ASK 路径抽检
- [ ] API Key 已更换默认 `s0-demo-key`；管理 API 走 `/api/v1/admin/*`

### SSE 断线续传（§6.1）
- [x] 每帧 `id: {sessionId}:{seq}`；短窗缓冲默认 30s（`amp.sse.replay-ttl-ms`）
- [x] 重连带 `Last-Event-ID` → 只补发 seq 之后的增量
- [x] 缓冲过期/未知 → `ERROR{code:SSE_RESUME_MISS}`，客户端用 **history 兜底**
- [x] Playground「续传 SSE」按钮
- [x] `mvn test` 全绿（44）

```powershell
# 流式后记录 id: agt_xxx:N，断线重连
curl -N -X POST .../messages:stream -H "Last-Event-ID: agt_xxx:N" ...
```

### External 最小集（注册 / 心跳 / Chat 转发）
- [x] `external_agent` 表；`POST /api/v1/admin/external-agents` 注册
- [x] `POST .../{id}/heartbeat`；超时 `UNHEALTHY`
- [x] **Chat 转发**：托管目录无此 agentId 时，转发 `POST {baseUrl}/api/v1/messages`
- [x] 约定外部响应 `{"reply":"..."}`（兼容 text/message）；请求头 `X-Callback-Key`
- [x] `mvn test` 全绿（46）

```powershell
# 注册后直接对话（非流式）
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/ext-demo/messages -Headers $H `
  -d '{"userId":"u1","sessionId":"agt_ext1","message":"hi"}'
# → {"source":"external:ext-demo","reply":"..."}
```

### Nacos 配置中心（T2 / S7）
- [x] 可选接入 `nacos-client`：拉取 + 监听，失败自动回退本地配置
- [x] 热更映射到 `amp.ops.*`（限流开关/额度、token 粗估）
- [x] 状态查询 `GET /api/v1/admin/nacos`（source=local | nacos:…）
- [x] Compose 预留 `nacos` 服务（默认注释）
- [x] `mvn test` 全绿（50）

```yaml
# Nacos DataId: amp-platform.properties（或 YAML）
rateLimitEnabled=true
rateLimitPerMinute=60
tokenCharsPerToken=3
```

```powershell
$env:AMP_NACOS_ENABLED='true'
$env:AMP_NACOS_SERVER='127.0.0.1:8848'
$env:AMP_NACOS_DATA_ID='amp-platform.properties'
# 启动后
curl -s http://127.0.0.1:18081/api/v1/admin/nacos -Headers $HA
```

### OTel 链路（S7）
- [x] `OtelBridge`：SDK + **OTLP** 导出；无 endpoint 时 **logging** 导出（本地自检）
- [x] AgentScope `OtelTracingMiddleware`：Agent / Model / Tool 链路
- [x] HTTP span：`chat.messages`（含 `amp.agent_id` / `amp.session_id`）
- [x] 状态 `GET /api/v1/admin/otel`
- [x] `mvn test` 全绿（53）

```powershell
# 本地（logging 导出，日志里可见 span）
$env:AMP_OTEL_ENABLED='true'
# 接 Collector
$env:OTEL_EXPORTER_OTLP_ENDPOINT='http://127.0.0.1:4317'
curl -s http://127.0.0.1:18081/api/v1/admin/otel -Headers $HA
```

### Loki 日志检索（T8）
- [x] 可选推送：`POST /loki/api/v1/push`（标签 sessionId/agentId/level）
- [x] 检索：`GET /api/v1/admin/loki/search?sessionId=`（LogQL `|= sessionId=`）
- [x] **本地日志兜底**（无 Loki 时 grep `sessionId=`）
- [x] 会话事件钩子：`chat.done` / `stream.done` → `SessionLogService`
- [x] Compose 预留 `loki`（默认注释）
- [x] `mvn test` 全绿（56）

```powershell
# 本地兜底
curl -s "http://127.0.0.1:18081/api/v1/admin/loki/search?sessionId=agt_x1" -Headers $HA
# 接 Loki
$env:AMP_LOKI_ENABLED='true'
$env:AMP_LOKI_URL='http://127.0.0.1:3100'
```

### MCP 真实连通
- [x] `McpConnectService`：stdio / sse / streamable_http 客户端
- [x] 探测 + **listTools**（真实 MCP 协议）
- [x] 登记 server 注入 `ToolsConfig.mcpServers`（init 4–5s 软失败，不挡 Agent build）
- [x] Admin：`POST /api/v1/admin/mcp-servers/{name}/test`、`GET .../{name}/tools`
- [x] 演示 Server：`scripts/mcp-demo-server.py`（工具 `echo_time`）
- [x] `mvn test` 全绿（57）

```powershell
# 1) 起演示 MCP
python scripts/mcp-demo-server.py
# 2) 登记
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/mcp-servers -Headers $HA `
  -d '{"name":"demo-mcp","transport":"stdio","command":"python","args":"scripts/mcp-demo-server.py","enableTools":"echo_time"}'
# 3) 真实连通
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/mcp-servers/demo-mcp/test -Headers $HA
# → {"status":"CONNECTED","toolCount":1,"tools":[{"name":"echo_time",...}]}
```

### Docker 沙箱（T7）
- [x] `DockerSandboxService`：一次性容器执行不可信命令
- [x] 安全边界：`--network=none`、`--read-only`、`--cap-drop ALL`、`no-new-privileges`、降权 `65534`、内存/CPU/PIDs 限制、超时强杀
- [x] IsolationScope 仅 **USER / SESSION**（GLOBAL 自动降级 USER）
- [x] 工具 `sandbox_exec`（默认 ASK）；**默认关闭** `amp.sandbox.enabled=false`
- [x] Admin：`GET /api/v1/admin/sandbox/status`、`POST .../exec`
- [x] 指标 `amp.sandbox.duration` / `amp.sandbox.execs`
- [x] `mvn test` 全绿（61）

```powershell
$env:AMP_SANDBOX_ENABLED='true'
$env:AMP_SANDBOX_IMAGE='alpine:3.19'
curl -s http://127.0.0.1:18081/api/v1/admin/sandbox/status -Headers $HA
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/sandbox/exec -Headers $HA `
  -d '{"command":"echo hello-from-sandbox"}'
```

### Remote FS BaseStore（T9）
- [x] `base_store` 表 + `PgBaseStore`（实现 AgentScope `BaseStore`）
- [x] `RemoteFilesystemSpec` + `DistributedStore`（State=Redis，Base=PostgreSQL）
- [x] 隔离：`amp.fs.isolation-scope` USER/SESSION；shared prefix `agents/{agentId}`
- [x] 乐观锁 `putIfVersion`；Admin `GET /api/v1/admin/fs/status|items`
- [x] 默认 `amp.fs.mode=remote`（多副本共享工作区）
- [x] `mvn test` 全绿（64）

```powershell
curl -s http://127.0.0.1:18081/api/v1/admin/fs/status -Headers $HA
# mode=remote backend=PostgreSQL base_store
# 双副本（18081/18082）共用同一 PG，工作区文件跨副本可见
```

### S9 计费配额 / External 流式（**已完成**）

| 能力 | 说明 |
| --- | --- |
| 月度配额 | 表 `tenant_quota`：`monthly_token_limit` / `monthly_request_limit`（0=不限） |
| 用量流水 | 表 `usage_ledger`：prompt/completion tokens、cost_micros、source |
| 超额 | `QUOTA_EXCEEDED`（对话前 check，后记账） |
| Admin | `GET/POST /api/v1/admin/quotas`、`GET /api/v1/admin/usage` |
| External 流式 | 托管无此 agent 时透传 `POST {base}/api/v1/messages:stream` SSE |
| 演示 | `scripts/demo-external-stream.ps1` + `scripts/external-sse-mock.py` |

```powershell
$HA = @{ Authorization = "Bearer s0-admin-key"; "Content-Type" = "application/json" }
# 设月度 100 万 token / 1 万次请求
curl -s -X POST http://127.0.0.1:18081/api/v1/admin/quotas -Headers $HA `
  -d '{"monthlyTokenLimit":1000000,"monthlyRequestLimit":10000}'
curl -s http://127.0.0.1:18081/api/v1/admin/usage?limit=20 -Headers $HA
```

External 侧需实现 SSE：`POST {baseUrl}/api/v1/messages:stream`（请求体同 messages，返回 `text/event-stream`）。本地 mock：

```powershell
python scripts/external-sse-mock.py          # 默认 :19099
powershell -File scripts/demo-external-stream.ps1
```

```powershell
# 切流/技能审 → Redis channel amp:events:runtime，其他副本 drain/evict
$env:AMP_EVENTS_ENABLED='true'   # 默认 true
$env:AMP_REPLICA_ID='replica-a'  # 建议每副本显式设置

# N4 检查（ready/auth scope/审计/凭证打码）
powershell -File scripts/n4-check.ps1

# 压测冒烟（延迟分位）
powershell -File scripts/smoke-load.ps1 -N 20 -Concurrency 4
```

### 债务清单

#### 已完成（原债务已关闭）

- SSE 断线 `Last-Event-ID` 续传（短窗重放 + history 兜底）
- MCP 端到端连真实 Server（最小连通；演示 `scripts/mcp-demo-server.py`）
- 多副本共享目录指针刷新（PG 指针 + **Redis pub/sub** 广播 drain/evict；`amp.events.enabled`）
- 技能灰度/环境过滤（`SkillCanaryFilter` + 配置/表列）
- 模型凭证管理最小闭环（库表 + 打码 API + `ModelCreationContext` 注入）
- RAG embedding 可插拔（hash / http，失败回退 hash）
- External 非流式 + **流式**透传（`messages` / `messages:stream`）
- 压测冒烟分位（`scripts/smoke-load.ps1`）
- **S9 计费配额雏形**：`tenant_quota` + `usage_ledger` + 超额拦截

#### 仍待

- Vault / 完整密钥托管（现用库表打码 + 环境变量）
- **pgvector ANN**（现为余弦全表）
- 完整计费账单导出
- 网关 Channel / 对接 AgentScope Service / Hosted
- `chat/completions` OpenAI 兼容 — **已实现**（`OpenAiCompatController`，open scope Bearer）
- MCP 生产 Server 真实接入（非演示 stdio）
- 全量压测 NFR（首 Token P95、200 并发校准；现仅冒烟分位）
