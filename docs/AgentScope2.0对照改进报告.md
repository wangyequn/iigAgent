# AgentScope 2.0 设计思路对照改进报告

> **对照对象（勘误后）**：AgentScope **Java** 2.0 官方文档（https://java.agentscope.io/v2/zh/docs/quickstart 及其目录）
> 对照基线：本项目 AgentScope Java **2.0.3**（`io.agentscope:agentscope-harness` 等 4 个 artifact）
> **勘误（第二轮复核时发现）**：本报告第一版误用了 **Python 版** AgentScope 文档（docs.agentscope.io）作对照基准。实现层结论（javap 对真实 jar 的字节码验证、全部运行态证据）不受影响；但「设计主线」框架、压缩默认值表述等已按 Java 文档修正，差异见文末「第四轮：Java 文档基准复核」。
> 方法：Java 官方文档 × 项目实现盘点 × harness 字节码验证（区分「harness 有能力没用」与「根本没有」）
> 日期：本报告所有行号以当前工作区代码为准

---

## 一、总览

AgentScope **Java** 2.0 的设计主线是三大主题（官方总纲）：**Harness 工程化**（技能仓库、分层记忆、子智能体、上下文自动管理、Plan Mode、Workspace）、**企业级分布式部署**（多租户隔离、安全沙箱、工具权限管控、优雅上下线与会话恢复）、**底层框架升级**（事件流式原生支持、消息模型、Middleware、HITL 一等公民），另有独立的 **AgentScope Service**（控制面/数据面产品）。逐项对照后，项目的差距可分三类：

| 等级 | 含义 | 数量 |
| --- | --- | --- |
| **P0** | 正确性缺陷，用户直接感知「不好用」 | 2 |
| **P1** | 架构对齐缺口，影响长任务稳定性与可观测性 | 3 |
| **P2** | 能力补齐，harness 已具备、项目未启用 | 5 |

一个总体判断：**项目把 AgentScope Java 当「对话执行引擎」用，而 2.0 把它设计成「带完整事件流、权限闭环、上下文自治的智能体运行时」。** 大部分差距不是要新写代码，而是 harness 已经提供、项目没有配置或没有接出来——这既是坏消息（我们漏用了），也是好消息（改进成本低）。

---

## 二、P0：两个正确性缺陷

### P0-1 HITL 确认后拿不到回复（已知未修复，官方设计有明确答案）

**官方设计**：权限返回 ASK 时智能体发出 `RequireUserConfirmEvent` 暂停；用户回传 `UserConfirmResultEvent` 恢复。恢复后三条路径都有明确定义——已确认的立即执行继续推理；**已拒绝的产生 LLM 可见的错误结果，LLM 可能换方式重试**；已接受的规则持久化。也就是说：无论确认与否，下一轮一定有产出（继续执行或错误回注后再推理），不存在「静默空回复」。

**项目现状**：`ChatController.java:437-438` 恢复调用 `lease.agent().call(...).block()` 后只处理 `result == null → ""`，**不判断 result 的工具状态**。实测恢复轮 `POST_ACTING | name=query_orders, result_len=156, state=ERROR` 后 Agent 重新调工具、会话停在工具轮，最终 `{"reply":""}` 且 HTTP 200。非流式接口（`POST .../messages`）只要本轮结束在工具调用上就返回 200 空串，没有任何错误码。

**建议**：
1. 在 confirm 恢复路径解析返回 `Msg` 中的 `ToolResultBlock`，`state=ERROR/DENIED` 时把错误原文写入日志与响应（先解出那 156 字节是什么，再定根因——这是上次排查遗留的下一步）；
2. 需要确认却未确认、或恢复失败时，非流式接口返回 `409` + `code` + `replyId`，不再静默 200 空串；
3. **补 confirm 端到端测试**——现有 156 个单测全绿但 HITL 全程无端到端覆盖（37 个测试文件中无一条走 `REQUIRE_USER_CONFIRM → confirm → 最终回复`），这正是「测试绿灯、实际不好用」的盲区。

### P0-2 权限建议规则闭环缺失：每次都要重复确认

**官方设计**：ASK 决策附带 **`suggested_rules`**（由本次调用自动生成），用户在 `ConfirmResult` 里接受后规则持久化进权限引擎，**相同调用今后自动放行**。规则来源分层（userSettings / projectSettings / session），配合 `match_rule()` 语义（Bash 前缀通配、文件 glob）。

**项目现状**：`ChatController.java:424-435` 构造 confirm 只回传 `confirmed` 布尔，没有 rules 通道；`GovernanceService` 的规则只能通过管理接口手写。结果是 `place_order` 这类 ASK 工具**每次调用都弹确认**，无法「信任一次，放行同类」。另外官方的工具级内置检查（`check_permissions()` / `check_read_only()`、`bypass_immune` 不可绕过的安全 ASK）在自定义工具（`OrderToolset` 等）上完全没有实现——工具自己不设安全红线，全靠外部规则表。

**建议**：confirm 端点增加可选 `rules` 字段，ASK 响应里由平台生成建议规则（如 `place_order` 的调用签名）一并返回给前端，用户勾选「以后不再询问」即写入 `permission_rule`（source='session'）。工具侧为写操作补 `check_permissions` 语义（可在 harness `ToolBase` 子类实现）。

---

## 三、P1：架构对齐缺口

### P1-1 事件系统：全量事件进、稀释事件出，前端无法重建消息

**官方设计**：事件是 2.0 的基石。每个事件带 `reply_id` / `block_id` / `tool_call_id`，遵循 **start → delta → end** 三段式；`Msg.append_event()` 可从任意检查点的事件流**完整重建消息**（断线重连、多端同步都靠这个）。HITL、中断、超限（`ExceedMaxItersEvent`）、全工具拒绝（`AllToolsDeniedEvent`）、调度注入（`HintBlockEvent`）全部是一等事件。

**项目现状**：`ChatController.java:284-301` 订阅的是 harness 全量 `AgentEvent` 流，但 `buildEventJson`（`:576-606`）**只给 `TextBlockDeltaEvent` 和 `RequireUserConfirmEvent` 附加了载荷字段**，其余事件只输出 `type/id/source`——`ThinkingBlockDeltaEvent` 的思考内容、`ToolCallDelta` 的参数增量、`ToolResultEndEvent` 的 `state`、`ExceedMaxIters`/`AllToolsDenied`/`HintBlock`/`CustomEvent` 的信息全部丢掉，前端收到的是空壳事件类型。SSE 续传是自建 `SseReplayBuffer`（30s 短窗、单订阅者），与官方「按会话消息总线 + 后接入重放缓冲 + 多订阅者扇出」差距明显。

**建议**：
1. 短期：`buildEventJson` 补齐各事件类型的载荷（delta、tool_call_id、state、replyId），让前端能渲染思考链与工具进度；
2. 中期：按 `reply_id + block_id` 组织帧结构，前端实现 `append_event` 等价物，消息可从事件流重建（history 接口退化为兜底）；
3. 关注 harness 的 `messageBus(...)` 能力（见 P2-4），把 replay buffer 升级为多订阅者会话流。

### P1-2 上下文与效率：全部吃默认值，从未按模型调参验证

**官方设计**：`ContextConfig(trigger_ratio, reserve_ratio, tool_result_limit)` 控制压缩与工具结果截断；`model_config` 控制重试与**备用模型回退**；工具按 `concurrencySafe` 分批并发。

**字节码验证**（`javap -c -p` 对 `agentscope-harness-2.0.3.jar`）揭示的真实状态——**能力都在，项目一个都没配**（`AgentInstanceFactory.java:153-273` 全程未调用下列 Builder 方法）：

| 能力 | harness 默认值（2.0.3 字节码） | 项目状态（对照时） |
| --- | --- | --- |
| `compaction(...)` | **默认安装**（`disableCompaction=false` + config 非 null + model 非 null 即装 `CompactionMiddleware`）：50 条消息或 token 触发（模型上报窗口时按 `窗口−reserved` 动态触发，未上报按 160k 兜底），压缩后保留 2k–8k token / 20 条 | 未显式配置，吃默认。**注**：Java 官方文档（面向新版本）表述为「默认关、按需 `.compaction(...)` 开启」，与 2.0.3 字节码不一致——显式配置可消除版本漂移（第四轮已改） |
| `toolResultEviction(...)` | **默认安装**（同上双条件），80K 字符阈值逐出 | 未显式配置，吃默认（同上，第四轮已显式化） |
| `maxContextTokens(int)` | 默认 **8000**（注：字节码验证为 **workspace 上下文注入预算**，非会话压缩窗口——初稿此处判断有误） | 未覆盖 |
| `maxRetries(int)` | ModelConfig 默认 **3** | 未显式配置 |
| `fallbackModel(...)` | 默认 **null**（无回退） | 未配置——DeepSeek 抖动即整链路失败 |
| `concurrencySafe` 工具并发 | 已生效（日志见同毫秒双 PRE_ACTING） | 已覆盖（3 个工具类有注解） |

也就是说：长对话压缩**实际上在工作**（2.0.3 默认安装且按模型窗口动态触发），但依赖隐式默认是脆弱的；而**模型故障回退是真空**——重试 3 次后没有备用模型可切。

**建议**：在 `AgentInstanceFactory` 显式配置 `compaction`（按 `AMP_MODEL_ID` 的窗口设 trigger/keep）、`maxContextTokens`、`fallbackModel`（如主用 deepseek-flash、备用配一个低价模型），并把当前生效值暴露到 `GET /api/v1/admin/config` 便于排查。补一个长会话压缩的集成测试。

### P1-3 工作区隔离：按版本分目录，租户与会话维度缺失

**官方设计**：Workspace 是 2.0 的独立支柱——Local/Docker/E2B 三后端同一接口，`WorkspaceManager` 决定隔离键（按 agent / user / session 可插拔），带 TTL 缓存与回收；offload 内容按 `sessions/{session_id}/` 隔离。

**项目现状**：`AgentInstanceFactory.java:156` 的本地 workspace 路径是 `{workspace}/v{version}`——**按发布版本分目录，不含租户、不含会话**。多租户共用同一版本目录时文件互相可见。`amp.fs.mode=remote` 下 `RemoteFilesystemSpec` 虽支持 USER/SESSION 隔离（`:229-244`），但与本地 workspace 路径是两套逻辑；harness 现成的 `WorkspaceManager.workspaceFor(user, agent)` 与 `sessions/{session_id}` offload 布局均未使用。

**建议**：workspace 路径至少变为 `{workspace}/{tenantId}/v{version}`，会话级产物（compaction offload、截断工具结果）走 `sessions/{sessionId}/`；启用 harness `WorkspaceManager` 统一管理生命周期。

---

## 四、P2：harness 已备、项目未启用的五项能力

以下能力经 `javap` 确认存在于 `HarnessAgent.Builder`，而 `AgentInstanceFactory` 从未调用：

1. **异步/后台工具卸载**——`asyncToolRegistry(...)` / `asyncToolTimeout(...)` / `messageBus(...)`，对应官方 `ToolOffloadMiddleware`：长耗时工具转后台、结果经总线回送唤醒会话。项目现状是工具全部同步执行，长工具占住 SSE 连接，断线即丢上下文。
2. **运行中断**——`HarnessAgent.interrupt()` 有完整重载（指定 session、带停止消息），对应官方「中断智能体」页。**勘误（2026-09-29 复核）**：平台其实已有 `POST .../sessions/{sid}/interrupt`（`ChatController.java`），实测调用后 2.7 秒内停流、会话可正常续聊——本条「未暴露 API」的判断是盘点失误，第 6 项无需开发。
3. **计划模式与任务清单**——`enablePlanMode()` / `enableTaskList()`（harness 内有 `PlanModeManager`），对应官方计划模式：复杂任务拆成可追踪清单。项目未启用。
4. **元工具与子智能体/团队**——`enableMetaTool(boolean)`（运行时按需激活工具组，解决工具越加越多撑爆 prompt）、`subagent(...)` / `teamsMode(...)`（对应官方 Agent Team）。项目工具全量常驻注册，团队编排只有自建 `LocalWorkflowRunner`。
5. **会话回放与多订阅者**——见 P1-1 第 3 点，`messageBus` 是底层依赖。

**中间件**补充一项：项目只挂了 `OtelTracingMiddleware`（官方等价 `TracingMiddleware`）。官方 6 个 hook 中的 `on_system_prompt` 尚未利用——适合注入动态上下文（当前时间、租户环境信息、配额余量），无需改写各 agent 的 sysPrompt；工具级审计也应从 HTTP 层 `AuditFilter` 下沉到 `on_acting`（现在工具调用级只有日志没有审计行）。

---

## 五、优先级路线图

| 顺序 | 事项 | 类型 | 预估 |
| --- | --- | --- | --- |
| 1 | 修复 HITL confirm 恢复：解析 ToolResult state、错误可见、非流式 409、补端到端测试 | 修 bug | 中 |
| 2 | confirm 增加建议规则通道，ASK 可一键「以后免问」 | 功能 | 小 |
| 3 | `buildEventJson` 补全事件载荷（delta/state/replyId） | 修 bug | 小 |
| 4 | 显式配置 compaction / maxContextTokens / fallbackModel 并验证 | 配置 | 小 |
| 5 | workspace 路径加租户维度 + 会话级 offload 目录 | 安全 | 小 |
| 6 | 暴露 interrupt API | 功能 | 小 |
| 7 | 异步工具卸载（asyncToolRegistry + messageBus） | 架构 | 大 |
| 8 | 事件流多订阅者回放（对齐官方会话流） | 架构 | 大 |
| 9 | 计划模式 / 元工具 / 子智能体按需启用 | 增强 | 中 |

第 1、3、4、5 项都属于「小改动、直接提升可用性」，建议作为下一轮开工内容；第 7、8 项涉及 SSE 契约变更，需与 `platform-console-ui` 协同设计后再动。

---

## 六、落地记录（本轮已实施）

路线图第 1、3、4、5 项与 2 的 UI 侧已完成，并通过端到端验证：

| 事项 | 实现 | 运行态证据 |
| --- | --- | --- |
| 1. HITL 空回复 | 根因：`confirm` 重建 `ToolUseBlock` 未设 `content`，`ToolValidator` 校验 null 报错 → 工具 ERROR → 模型重试再 ASK → 返回空串。三处重建补 `content`；恢复改走 `streamEvents` 收集事件 | `POST_ACTING place_order state=SUCCESS`；confirm 返回 `ORD-5380` 真实订单回复 |
| 2a. 非流式 409 | `doMessage`/`confirm` 暂停时抛 `HITL_CONFIRM_REQUIRED` → 409；审批经事件落库（带去重） | `HTTP 409 {"code":"HITL_CONFIRM_REQUIRED",...}`，`GET .../approvals` 可查到 PENDING |
| 2b. 前端 | `post()` 错误带 `status`；409 时刷新审批面板；`confirmPending` 修复 `toolInput` 解析 | — |
| 3. 事件载荷 | `buildEventJson` 补全 Thinking/Text/Tool/Model/ExceedMaxIters 等字段；前端读 `delta` 累积 | `TOOL_RESULT_END ... state=SUCCESS`、`THINKING_BLOCK_DELTA` 带 delta/replyId/blockId |
| 4. 运行时配置 | `amp.agent.compaction-trigger-tokens/fallback-model-id/max-retries/max-context-tokens` 显式进 `application.yml` 并由 Builder 应用 | `mvn verify` 156 测试全绿 |
| 5. workspace 租户维度 | `create(tenantId, ...)` 接请求租户；路径 `{base}/{tenant}/v{version}`（旧裸路径视同未设置） | build 日志 `workspace=.agentscope\workspace\acme\v4` |

回归：`mvn -B verify` EXIT=0（156 tests）。契约变更详见 `AGENTS.md` 对应条目。

### 第二轮：路线图 6–9（2026-09-29）

| 项 | 结果 | 运行态证据 |
| --- | --- | --- |
| 6. interrupt API | **勘误：已存在且有效**，无需开发 | 调用后 2.7s 停流；中断后同会话续聊正常 |
| 9. 计划模式/元工具/任务清单 | 三个配置开关（默认关）+ build 日志与工具清单可观测 | 开启时 `agentscope 2.0 features ...`、工具 4→9（plan_enter/exit、todo_write、reset_equipped_tools）；关闭后回到 4 |
| 7. 异步工具卸载 | 平台链路全部接通（共享 MessageBus、`async-tool-timeout-ms`、`WakeupDispatcher`+空消息恢复+事件扇出）；**但 harness 2.0.3 完成注入与 DeepSeek 严格配对不兼容（重复 tool_result→400），默认关闭** | 日志链完整：offload→completed→inbox→waking；Redis 状态可见占位符+重复结果两条 TOOL；`wakeup run failed` 已补错误日志 |
| 8. 会话事件流多订阅者回放 | `SessionEventHub` + `GET .../sessions/{sid}/stream`（回放+实时），POST 触发流、非流式回合、唤醒恢复三路统一扇出 | GET 订阅者与 POST 触发端各收 73 帧完全一致；非流式回合扇出 9 帧 |

回归：`mvn -B verify` EXIT=0（**160 tests**，新增 SessionEventHubTest 4 条）；HITL 全链路（409→confirm→ORD-2230）与默认态冒烟在最终实例复验通过。

### 第三轮：路线图欠账与同坑修复（2026-09-29）

| 事项 | 结果 | 运行态证据 |
| --- | --- | --- |
| 第 2 项：ASK 建议规则通道（补做） | `suggestedRules` 下发 + `acceptRule` 协议 + 平台落库（替换 ASK）+ 驱逐 + **会话状态重同步** + 前端「不再询问」勾选；范围租户级 | 端到端：409 → confirm(acceptRule) ORD-4691 → 同会话再下单**免问** ORD-3803；日志 `persisted → evict → resynced 81 个会话状态` |
| 关键发现：状态压制规则 | `ConfirmResult.rules` 不写入状态（实证）；且引擎优先读 Redis `permission_context`，旧 ask 不被重建覆盖 → 新增 `SessionStateRuleScrubber`（管理台改规则同样接入） | DB 仅剩 ALLOW 仍 409 → 状态含 ask+allow 并存（ASK 优先）→ 重同步后立即生效 |
| OpenAI 兼容同坑修复 | 非流式暂停 409 + OpenAI error 结构；流式发 error chunk；审批落库/会话登记/事件扇出对齐 | `TURN2: 409 {"error":{...,"code":"HITL_CONFIRM_REQUIRED"}}` |
| confirm 端到端自动化测试（补做） | `ChatConfirmContractTest` 4 用例：409+审批落库、恢复 content 回填（防 P0 回归）、acceptRule 规则回传、approvals 规则下发 | `mvn verify` EXIT=0（**164 tests**） |

### 第四轮：Java 文档基准复核（2026-09-29，勘误）

第一版对照误用 Python 文档；换用 Java 官方文档（java.agentscope.io/v2/zh）复核后，结论修正与新增如下。

**被证实的结论（Java 文档背书，无需改动）：**

| 既有结论 | Java 文档佐证 |
| --- | --- |
| `ruleContent=null` 即匹配该工具全部调用（星号修复方向） | 权限页明文：「`null` 表示对该 tool 的所有调用均匹配」 |
| 事件载荷补全的字段设计（replyId/blockId/toolCallId/delta/state） | 消息与事件页事件表逐项一致；「事件流可重建消息」为官方范式 |
| HITL 走 `RequireUserConfirmEvent` + `ConfirmResult` 挂 metadata 恢复 | 权限页 Streaming 模式与项目用法逐字吻合 |
| 权限三态决策 DENY→ASK→ALLOW、Mode 五档 | 权限页决策流程图一致 |
| `PermissionDecision` 四态、`checkPermissions` 运行时检查 | 权限页 Built-in Checks 一致（Java 版为**不可绕过**语义） |

**修正与更新：**

1. **压缩默认值**：Java 文档称「默认是关的，按需开启」，2.0.3 字节码却是默认安装（双条件已验证）——差异应为版本演进或文档推荐口径。**第四轮已改为显式配置**：`compaction(triggerMessages=30, keepMessages=10)`（对齐官方 quickstart 示例）+ `toolResultEviction(defaults())`，yml 新增 `compaction-trigger-messages` / `compaction-keep-messages` / `tool-result-eviction` 三键，构建日志打印生效参数，消除版本升级时的隐式默认漂移。
2. **建议规则的官方载荷位置**：Java 文档写 `suggestedRules` 位于 `ToolUseBlock` 上（`toolCall.getSuggestedRules()`），但 2.0.3 的 `ToolUseBlock` 字节码**无此字段**——平台「`generateSuggestions` 自生成 + permission_rule 落库」的实现是当前版本下唯一可行路径，等上游对齐后可切换为透传官方载荷。

**全量页面复核（15 篇，三组并行抓取，2026-09-29）**——除上文抽查 5 篇外，补齐 Building Blocks / Harness / 能力页全部核心章节：

*已覆盖（与官方一致，无需改动）*：Agent（无状态引擎+按会话状态、HITL/interrupt）、Context & AgentState（Redis 按 (user,session) 加载、`tenant_user` 隔离）、Harness 架构（薄包装+内置中间件链）、Workspace（AGENTS.md/会话日志/状态独立于工作区）、Filesystem（remote 模式+IsolationScope=USER+DistributedStore=Redis/PG 对齐官方一键注入）。

*做法差异（自研替代，语义等价，维持现状）*：Model 凭据走自建 `ModelCredentialService`（官方 Credential/ChatModel 两层）；Skill 走 S5 自研市场（APPROVED 只读+审核+灰度）vs 官方四层合成+curator 自学习环；Channel 走自研 SPI+webhook 直调 `streamEvents` vs 官方 HarnessGateway/ChannelManager（无 per-session 排队——已由平台会话登记兜底）；子智能体禁用、以二期 Workflow/Team 替代 harness `agent_spawn`。

*已知边界（默认关/未启用，按需评估）*：Middleware 仅挂 Otel（官方 5 hook 其余未用）；沙箱未用官方 `snapshotSpec`/`executionGuard`（快照跨调用恢复与多副本 slot）；`fallbackModel` 仍空；异步卸载维持关闭（2.0.3+DeepSeek 已知不兼容）。

*第四轮新增差距（合并后全表）*：

| # | 差距 | 来源页 | 量级 |
| --- | --- | --- | --- |
| 1 | `Msg.getGenerateReason()` 未读取/透出（`PERMISSION_ASKING` 等退出原因一等公民） | 消息与事件 / Agent | 小 |
| 2 | 全部工具被拒绝后空转：未装 `onActing`+`AllToolsDeniedEvent`→`RequestStopEvent` 停止配方 | 权限 / 事件 | 小 |
| 3 | `UserConfirmResultEvent` 恢复事件未转发 SSE（前端无法关联确认结果与原暂停事件） | 权限 / 事件 | 小 |
| 4 | `clearContext`（按会话清上下文）无管理端点（harness 有 API） | Context | 小 |
| 5 | permission_rule 残留死规则：`memory_*`/`session_search`/`read_file` 对应工具已 `disableMemoryTools/DisableFilesystemTools` 但 ALLOW 行仍在（连带 CatalogBootstrap seed，清理需一并改种子） | 记忆 | 小 |
| 6 | Plan Mode 开关已加但默认关：`plan_exit` 的 HITL 确认链路未接、开启后行为未实测 | Plan Mode | 小 |
| 7 | 外部执行工具链路（`isExternalTool` → `RequireExternalExecutionEvent` → 外部结果回注）未接 | Tool | 中（按需） |
| 8 | 沙箱未接官方 `snapshotSpec`/`executionGuard`（快照恢复、多副本执行锁） | Sandbox | 中 |
| 9 | 跨主机多副本前需换分布式 `MessageBus`（现为同机 WorkspaceMessageBus）+ 补 GracefulShutdown 清单核对 | 上线指南 | 中 |
| 10 | DataBlock 多模态事件未序列化（无场景，备忘） | 消息与事件 | 备忘 |
| 11 | `session_list/session_history/session_search` 工具未评估启用（压缩后自查原始日志） | 压缩 | 可选 |

复核方法备注：15 篇由三组并行子代理抓取压缩汇报（每篇≤130 字），抽查 5 篇（quickstart/总纲/权限/事件/压缩）为精读；Service 章节按战略备忘处理未逐页展开。

### 第五轮：第四轮差距首批落地（2026-09-29）

| 差距（对应第四轮总表 #） | 实现 | 运行态证据 |
| --- | --- | --- |
| #4 `clearContext` 无端点 | `POST .../clear-context`（lease 内调 harness `clearContext(ctx)`）+ 前端「清上下文」按钮 | 历史 2 条 → 清后 0 条；追问「上一条回复了什么」答「本会话之前没有发过任何消息」（失忆成立，stateStore 持久化） |
| #1 GenerateReason 透出 | 非流式回复体 `generateReason` 字段 + 409 message 附 `(generateReason=...)` | 正常回复 `MODEL_STOP`；暂停 409 `generateReason=PERMISSION_ASKING` |
| #2 全拒停止配方 | `StopOnAllDeniedMiddleware`（官方示例原文）挂 builder | 拒绝 place_order 后返回 `generateReason=ALL_TOOLS_DENIED`、reply 空（本轮立即停止，未再空转推理） |
| #5 死规则清理 | 删 4 条种子（memory_search/session_search/memory_get/read_file）+ psql 删存量 4 行 | 重启后 `GET permissions` 仅 3 条（place_order ASK / query_orders、knowledge_search ALLOW），未复活 |
| 附带发现：种子覆盖用户规则 | `seedRuleIfAbsent` 由 tool+behavior 判存在改为 **toolName 级**——原逻辑在重启时会补种 ASK 覆盖用户 acceptRule 接受的 ALLOW（upsertRule 行为替换） | 代码修复 + AGENTS.md 记坑 |

回归：`mvn -B verify` EXIT=0（164 tests）；最终实例 r-26752 四项 E2E 全过。

## 附：关键验证命令

```powershell
# harness Builder 能力清单（确认哪些能力存在）
& "$env:JAVA_HOME\bin\javap.exe" -classpath D:\Repository\io\agentscope\agentscope-harness\2.0.3\agentscope-harness-2.0.3.jar 'io.agentscope.harness.agent.HarnessAgent$Builder'

# 压缩默认值（triggerMessages=50 / keepTokens 2k-8k / FALLBACK_TRIGGER_TOKENS=160000）
& "$env:JAVA_HOME\bin\javap.exe" -constants -classpath ... 'io.agentscope.harness.agent.memory.compaction.CompactionConfig'

# 项目实际调用了哪些 Builder 方法
# → grep AgentInstanceFactory.java 中的 compaction|fallbackModel|maxContextTokens|interrupt 等，均无命中
```

官方文档索引（Java 版）：https://java.agentscope.io/llms.txt ；中文全目录页见各 `/v2/zh/docs/**` 链接。
（第一版误引的 Python 索引 https://docs.agentscope.io/_llms/zh/agent-scope.md 仅作历史参照。）
