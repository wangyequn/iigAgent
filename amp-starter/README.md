# amp-starter · AgentScope Java 2.0 金路径

> **一句话**：整目录拷走 → 配 Key → `mvn spring-boot:run` → curl SSE → 改提示词 → 挂工具 → 过 HITL。  
> 对齐中台踩坑后的最佳实践；**不依赖** `platform-*`（Copy 模式）。

| 项 | 说明 |
| --- | --- |
| 底座 | AgentScope Java **2.0.3**（Harness + Redis State + 模型扩展） |
| 形态 | Spring Boot 3 / JDK 21 |
| 适用 | 业务系统内嵌单 Agent；公卫等系统工具接入起点 |
| 不适用 | 多租户治理、技能市场、RAG、控制台 → 用完整中台 |

---

## 10 分钟跑通

```powershell
# 1) Redis
docker compose up -d          # 或本机 redis-server:6379

# 2) 模型 Key
$env:DEEPSEEK_API_KEY = "sk-..."

# 3) 启动（默认 :18085）
mvn -pl amp-starter -am spring-boot:run
# 拷走独立工程时：mvn spring-boot:run
```

### 非流式对话

```powershell
curl -s -X POST http://127.0.0.1:18085/api/v1/agents/demo/messages `
  -H "Content-Type: application/json" `
  -d '{"tenantId":"acme","userId":"u1","sessionId":"sess-1","message":"你好"}'
```

### 流式 SSE

```powershell
curl -N -X POST http://127.0.0.1:18085/api/v1/agents/demo/messages:stream `
  -H "Content-Type: application/json" `
  -d '{"tenantId":"acme","userId":"u1","sessionId":"sess-2","message":"介绍你自己"}'
```

> **坑**：流式路径是 **`messages:stream`（冒号）**；浏览器 EventSource 不支持 POST，请用 `fetch`。

---

## 场景一 · 改系统提示词

`src/main/resources/application.yml` → `amp.agent.sys-prompt`  
或启动参数 `AMP_SYS_PROMPT=...`。  
复杂人设放到 `prompts/default-system.md`，在配置里引用即可。

## 场景二 · 加一个业务工具

1. 在 `tool/` 下仿 `SampleTools` 写 `@Tool` 方法；  
2. `HarnessFactory.build` 里 `agent.getToolkit().registerTool(new YourTools());`；  
3. 按需把 `demoPermissionContext()` 改成你的规则（只读 ALLOW / 写 ASK）。

```java
@Tool(name = "query_registry", description = "查询档案摘要", readOnly = true, concurrencySafe = true)
public String queryRegistry(@ToolParam(name = "id", required = true) Object id) {
    // 调你的公卫系统服务；注意脱敏
    return "{\"ok\":true}";
}
```

## 场景三 · HITL 确认闭环

`dangerous_write` 默认 **ASK**。模型要写时会暂停：

1. `messages` 返回 `awaitConfirm=true` + `pendingTools`（含 `toolInput` JSON 字符串）；  
2. 客户端 **`JSON.parse(toolInput)`** 后放进 `toolCalls[].input`（必须是对象，不是字符串）；  
3. `POST .../confirm`：

```powershell
curl -s -X POST http://127.0.0.1:18085/api/v1/agents/demo/sessions/sess-3/confirm `
  -H "Content-Type: application/json" `
  -d '{
    "tenantId":"acme","userId":"u1",
    "note":"同意写入",
    "toolCalls":[{
      "toolCallId":"call_1","toolName":"dangerous_write",
      "input":{"target":"demo"},"confirmed":true
    }]
  }'
```

### ★ 必踩坑：confirm 必须回填 `ToolUseBlock.content`

引擎 `ToolExecutor` 用 `block.getContent()`（**入参 JSON 串**）做 schema 校验。  
只设 `input` 会报 `argument "content" is null` → 工具 ERROR → 模型重试 → **回复恒空**。  
本 starter `ChatController.confirm` 已 `.content(toJson(input))`，抄代码时不要删掉。

---

## 会话与多副本

| 规则 | 说明 |
| --- | --- |
| `userId` + `sessionId` | 必填；`sessionId` 须 `[A-Za-z0-9_-]{1,128}` |
| 状态 | **Redis**（`RedisAgentStateStore`），杀进程/换副本会话可续 |
| 并发 | 同 `(userId, sessionId)` 串行由框架保证 |

---

## 权限三态（坑）

| 语义 | 正确写法 | 错误写法 |
| --- | --- | --- |
| 匹配该工具全部调用 | `ruleContent = null` | `ruleContent = "*"` **不会命中** |

`HarnessFactory.normalizeRuleContent`：`"*"` → `null`。库/配置里习惯写 `*` 时务必经过此函数。

全拒工具后：`StopOnAllDeniedMiddleware` 以 `ALL_TOOLS_DENIED` 停止本轮，避免空转。

---

## 生产检查单

- [ ] 模型 Key 走环境变量 / 密钥管理，**不要**写进仓库  
- [ ] 生产 `redis` 主从 + AOF（会话 RPO/RTO 目标）  
- [ ] 写操作工具默认 ASK；只读才 ALLOW  
- [ ] 业务返回做脱敏（身份证/手机号等）  
- [ ] 网关/入口层鉴权（本 starter 未内置 API Key，上生产务必加）  
- [ ] 观测：日志至少带 `sessionId`；需要时接中台 OTel  
- [ ] 压测：固定 prompt 测首 Token / P95  

---

## 和中台的关系

| 你需要 | 用 |
| --- | --- |
| 业务系统嵌 Agent、自己发版 | **本 starter** |
| 多租户、目录发布、技能审核、知识库、审计配额 | 中台 `platform-open-api` |
| 公司统一「业务系统怎么接机器人」 | 中台工具治理 + 本 starter 作接入范式 |

---

## 契约测试

```powershell
mvn -pl amp-starter test
```

覆盖：session 校验、权限 `*`→null、**confirm content 回填**、HTTP 契约子集。

---

## 目录

```text
amp-starter/
├── pom.xml                 # 仅官方 agentscope-* + Spring Boot
├── docker-compose.yml      # Redis
├── .env.example
└── src/main/java/com/example/agent/
    ├── StarterApplication.java
    ├── config/             # AgentProperties / AgentConfiguration
    ├── runtime/            # HarnessFactory · AgentSessions · StopOnAllDeniedMiddleware
    ├── session/            # SessionContext
    ├── tool/               # SampleTools
    └── web/                # ChatController · AgentEventJson · ApiExceptionHandler
```
