# 登录平台（pc_platform）接入 AI Agent 方案

> **目标：** 把 **pc_platform（登录/权限平台）** 接入本 AI 中台，使登录平台及其业务页面具备 AI 能力（智能问答、业务工具调用、知识检索、审批协同），且 **智能体权限 ⊆ 当前登录用户权限**。
>
> **形态：** `jeecg-boot-master`（微服务）+ `ant-design-vue-jeecg`（前端）  
> **对接方：** 本仓库 AI 中台（AgentScope Harness + Open API）  
> **文档定位：** 总接入方案（一页讲清「怎么接、接什么、分几步」）；细节契约见文末「相关文档」。

---

## 1. 一句话结论

**pc_platform 继续做唯一的登录与权限中心；AI 中台作为「子系统 + 智能体运行时」被接入：用户在登录平台拿 `X-Access-Token`，机器人挂进页面并透传该票调用 Agent；Agent 工具再透传同一张票回业务系统——登录体验不变，平台多一层 AI 能力。**

```text
用户（已在 pc_platform 登录，持 X-Access-Token）
        │
        ▼
pc_platform 页面 ──挂载──► <AiBot />  /  业务按钮「问 AI」
        │                       │
        │                       │  X-Access-Token
        │                       ▼
        │              AI 中台（本仓库）
        │              ① 认票（loginByToken / 本地缓存）
        │              ② 取人（username / userId / 租户）
        │              ③ 取权（getUserPermissionByToken）
        │              ④ Agent 对话 / 工具 / 知识 / HITL
        │                       │
        │                       │  同一张 X-Access-Token
        ▼                       ▼
   业务微服务 ◄──────────────────┘
   （Shiro JwtFilter 原样鉴权 + 数据权限）
```

---

## 2. 目标与非目标

### 2.1 目标（「平台有 AI 能力」具体指什么）

| 能力 | 用户感知 | 落地接口 |
| --- | --- | --- |
| **对话助手** | 页面右下角/悬浮机器人，自然语言问业务 | `POST .../messages:stream`（SSE） |
| **业务工具** | 「查我的 XX」「导出报表」等由 Agent 调业务 API | `HttpBusinessTool`（透传用户票） |
| **知识问答** | 制度/手册/操作指引 RAG | `knowledge_search` + 管理台知识库 |
| **人工确认** | 写/高危操作弹确认 | HITL `confirm` / `approval_item` |
| **开放 API** | 其它系统可编程调用同一 Agent | OpenAI 兼容 `POST /api/v1/chat/completions` |

### 2.2 非目标

- **不**在 AI 中台另建账号体系（登录仍只走 pc_platform）。
- **不**用管理员/系统账号代替用户调业务（权限不放大）。
- **不**自动改造存量业务鉴权；**未治理接口不得进 Agent 工具目录**。
- 一期不做跨子系统「大一统」全量路由（见《大一统智能体平台集成方案》二期）。

---

## 3. 现状盘点（可复用，少造轮子）

| 模块 | 位置 | 状态 | 说明 |
| --- | --- | --- | --- |
| Jeecg 认票 | `JeecgAuthFilter` + `JeecgAuthClient` | **已有** | 读 `X-Access-Token`，`loginByToken` 远程校验，人轨优先于 API Key |
| Jeecg 配置 | `amp.jeecg.*`（`JeecgAuthProperties`） | **已有** | clientId=`ai-bot`，认票路径 `/sys/loginByToken`，权限码 `/sys/permission/getUserPermissionByToken` |
| SSO / 门户带票 | `platform-console-ui/auth/{sso,platformLogin}.js` | **已有** | 支持 query `token`/`code` → 本地保存 `X-Access-Token` |
| 用户票透传 | `HttpBusinessTool` / `McpConnectService` | **已有** | `authMode=user_token` 时注入 `X-Access-Token` |
| 对话流 | `ChatController`：`messages` / `messages:stream` / `confirm` / `interrupt` | **已有** | SSE 帧、续传、HITL 409 |
| OpenAI 兼容 | `OpenAiCompatController` | **已有** | `POST /api/v1/chat/completions`（含 stream） |
| 控制台机器人 | `ConsoleBot.vue` + `botBus.js` | **已有（可参考）** | 悬浮助手交互范式，可改造为业务侧 `AiBot` |
| 工具治理 | `business_tool` + Admin `/api/v1/admin/business-tools` | **已有** | HTTP 工具登记、权限 ASK/ALLOW/DENY |

**缺口（本方案要补的）：**

1. pc_platform 侧：`sys_sub_system` 注册 `ai-bot`、角色绑定、网关路由到 AI 服务。  
2. AI 侧业务工具：首批只读接口目录（permCode 对齐 `@RequiresPermissions`）。  
3. 业务前端：可复用的 `AiBot` 组件（Vue2 / Ant Design Vue），打进 `ant-design-vue-jeecg`。  
4. 联调与验收：合法/伪票、权限子集、数据范围、审计。

---

## 4. 接入原则（硬约束）

1. **身份只认登录平台票**：`X-Access-Token`；AI 不签发用户登录 token。  
2. **权限子集**：Agent 可执行 = 用户功能权限 ∩ Agent 工具面 ∩ 环境策略（只读/审批/租户）。  
3. **用户票透传**：调业务工具时打回**同一张** `X-Access-Token`，禁止默认高权 Key。  
4. **数据权限在业务侧**：过滤仍走 `PermissionDataAspect` / 业务 SQL；Agent 只传身份与范围参数。  
5. **未治理不进目录**：只藏按钮、无服务端鉴权的接口禁止上架 Agent。  
6. **写/高危默认 HITL**：`scope=write` 或 `risk>=medium` 默认 ASK，可按工具配置。

---

## 5. 接入形态（三种，可并行）

### 5.1 形态 A：页面嵌入机器人（主路径，让「平台有 AI」）

```vue
<!-- ant-design-vue-jeecg 全局布局 / 指定业务页 -->
<AiBot
  :token="Vue.ls.get(ACCESS_TOKEN)"
  client-id="ai-bot"
  :user-id="userId"
  :agent-id="'pc-assistant'"
  :ai-base="aiBaseUrl"
/>
```

- 位置：左下角/右下角悬浮（可配置）；主题色随 pc_platform。  
- 请求：`POST {aiBase}/api/v1/agents/{agentId}/messages:stream` + `X-Access-Token`。  
- **禁止**前端写死平台 API Key。  
- 交互可参考现网 `ConsoleBot`（审批条、建议动作、多轮）。

### 5.2 形态 B：业务 API / 服务端调用（让系统「会说话」）

| 场景 | 接口 | 凭证 |
| --- | --- | --- |
| 同步问答 | `POST /api/v1/agents/{agentId}/messages` | `X-Access-Token` 或 API Key |
| 流式问答 | `POST .../messages:stream` | 同上 |
| 标准协议 | `POST /api/v1/chat/completions`（`model`=agentId） | 同上 |
| 会话恢复/多端 | `GET .../sessions/{sid}/stream` | 同上 |

适用于：工单摘要、表单辅助填写、审批意见生成、消息通知里的 AI 段落。

### 5.3 形态 C：工具回写业务（Agent 会「动手」）

1. 业务接口治理后登记为 `business_tool`（`kind=HTTP`）。  
2. `config`：`url` / `method` / `headers` / `parameters`；`authMode=user_token`。  
3. 权限：`permission_rule` 绑定工具；危险写默认 ASK。  
4. 执行：Agent → `HttpBusinessTool` → 业务服务（带用户票）→ 原鉴权/数据权限生效。

---

## 6. 鉴权与身份（登录平台侧）

### 6.1 推荐：模式 B · OAuth2 授权码 + loginByToken（与现网 `amp.jeecg` 对齐）

```text
1. 用户访问 AI 入口（或已在门户）
2. 未登录 → 跳 pc_platform：GET /sys/oauth2Server/getCode?clientId=ai-bot
3. 登录/已登录授权 → code
4. AI/前端：GET /sys/oauth2Server/getTokenByCode?code&client_id&client_secret
5. 得到 Jeecg JWT（X-Access-Token）
6. 后续请求均带 X-Access-Token
7. AI 后端：POST /sys/loginByToken 认票 → username/userId
8. 需要权限时：POST /sys/permission/getUserPermissionByToken
```

### 6.2 降级/快捷：模式 A · 门户/网关带票

用户已在登录平台登录，页面或网关把 `X-Access-Token` 带给 AI（query `token` / 请求头）。实现量最小，适合一期演示与内部网关同域部署。

### 6.3 凭证优先级（AI 中台）

```text
X-Access-Token（pc_platform） > Authorization: Bearer（OIDC JWT） > Authorization: Bearer（API Key）
```

### 6.4 登录平台侧最小改动

| 改动 | 必要性 | 说明 |
| --- | --- | --- |
| `sys_sub_system` 插入 `ai-bot` | **必须** | `getCode` / `isPermitted` 认识机器人 |
| `sys_sub_system_role` 角色绑定 | **必须** | 控制哪些角色可用 AI |
| 网关路由 → AI 中台 | **推荐** | 同域统一入口，简化 CORS/带票 |
| 只读「认票/用户信息/权限码」接口 | **必须** | 现网已有 `loginByToken` / `getUserPermissionByToken` 可复用 |
| 业务权限码/Swagger 文档 | **推荐** | 生成工具 `permCode` |

业务微服务代码**可不改**（继续收 `X-Access-Token`）。

---

## 7. 能力建设路径（登录平台「有 AI」的产品面）

| 优先级 | 能力包 | 依赖 | 交付物 |
| --- | --- | --- | --- |
| P0 | 登录接入 + 基础对话 | 认票 + `messages:stream` | 悬浮 `AiBot`，能聊、能带身份 |
| P0 | 平台知识问答 | RAG 文档入库 | 制度/操作手册问答，带引用 |
| P1 | 业务只读工具 | 工具目录 2～3 个 | 「查我的 XX」真实出数 |
| P1 | 写操作 + HITL | `permission_rule` + confirm | 删除/提交类先确认再执行 |
| P2 | OpenAI 兼容给其它系统 | API Key / 用户票 | 下游系统编程接入 |
| P2 | 大一统意图路由 | 多 Agent / 多子系统 | 见《大一统智能体平台集成方案》 |

---

## 8. 接口与配置草图

### 8.1 对话（业务前端）

```http
POST /api/v1/agents/pc-assistant/messages:stream
X-Access-Token: <Jeecg JWT>
Content-Type: application/json

{
  "userId": "zhangsan",
  "sessionId": "sess_001",
  "message": "帮我查一下我名下待办工单"
}
```

### 8.2 AI 中台配置

```yaml
amp:
  jeecg:
    enabled: true
    base-url: ${JEECG_BASE_URL:}          # 空=仅本地/兼容；有值=远程 loginByToken
    portal-url: ${JEECG_PORTAL_URL:http://127.0.0.1:3200}
    client-id: ai-bot
    client-secret: ${JEECG_CLIENT_SECRET:}
    token-header: X-Access-Token
    # 默认路径已按实测对齐：
    # getCode / getTokenByCode / loginByToken / getUserPermissionByToken
```

### 8.3 工具登记示例（HTTP）

```json
{
  "name": "query_my_tickets",
  "kind": "HTTP",
  "config": {
    "url": "http://jeecg-gateway/ticket/api/my",
    "method": "GET",
    "authMode": "user_token"
  },
  "permission": { "behavior": "ALLOW", "ruleContent": null }
}
```

---

## 9. 实施计划

### M1 身份打通（约 0.5～1 周）

- [ ] pc_platform：`sys_sub_system` 注册 `ai-bot` + 角色绑定  
- [ ] AI：配置 `amp.jeecg.*`，联调 `loginByToken`  
- [ ] 前端：门户带票或 OAuth2 code 换票（`platformLogin.js`）  

**验收：** 无票 401；伪票/过期 401；合法票能解析 username；人轨优先于 API Key。

### M2 页面 AI 入口（约 1 周）

- [ ] 抽取/实现 Vue2 `AiBot`（对齐 `ConsoleBot` 交互）  
- [ ] 挂到 `BasicLayout` 或试点业务页  
- [ ] SSE 流式对话 + 历史/清上下文  

**验收：** 登录用户在业务页可多轮对话；请求头仅用户票，无平台密钥。

### M3 知识 + 只读工具（约 1～2 周）

- [ ] 管理台导入制度/手册 → RAG  
- [ ] 登记 2～3 个只读 `HttpBusinessTool`（`user_token`）  
- [ ] 权限码对齐 `@RequiresPermissions`  

**验收：** 无权限用户看不到对应工具；越权调用 403；数据范围与页面手工一致。

### M4 写操作与治理（约 1～2 周）

- [ ] 写/导出/删除默认 ASK + HITL 确认  
- [ ] 审计：username、clientId、tool、params  
- [ ] 配额/限流、OpenAI 兼容对外开放  

**验收：** 写操作必确认；审计可按用户检索；超额配额返回 `QUOTA_EXCEEDED`。

### M5 大一统（后续）

意图 → 多领域 Agent；跨业务组合；见专项方案。

---

## 10. 风险与对策

| 风险 | 对策 |
| --- | --- |
| Token 有效期约 1h | 组件静默续期/失败引导重新登录；长任务用会话恢复 |
| HMAC secret / client_secret 泄露 | 环境变量/密钥托管，勿进 Git；优先网关验签后可信头 |
| 权限码与按钮不一致 | 目录以 `@RequiresPermissions` 为准，管理台人工确认 |
| 只藏按钮的无鉴权接口 | 未治理禁止进目录（原则 5） |
| 数据权限被绕过 | 工具层传范围参数 + 业务 SQL 强制；禁止空条件全表 |
| `JiangSuPlatform` 等分支差异 | 环境开关进配置；联调覆盖 |
| 前端误带平台 Key | Code review + 组件封装禁止明文 Key |

---

## 11. 总验收清单

- [ ] `sys_sub_system` 含 `ai-bot`，未授权角色被拒  
- [ ] `X-Access-Token` 合法/非法/过期行为正确  
- [ ] Agent 调业务接口与页面手工调用**同等鉴权与数据范围**  
- [ ] 无权限用户看不到/调不了对应工具  
- [ ] 写操作 HITL 确认后方可执行  
- [ ] 审计可按 username 检索  
- [ ] 前端仅使用登录平台 token，无平台 API Key  
- [ ] OpenAI 兼容与 `messages:stream` 均可调通  

---

## 12. 与既有文档的关系

| 文档 | 关系 |
| --- | --- |
| [pc_platform登录鉴权接入方案](./pc_platform登录鉴权接入方案.md) | **登录/认票/取权** 细节（本方案 §6 的展开） |
| [pc_platform接入方案](./pc_platform接入方案.md) | **工具目录、AiBot 挂载、配置** 细节（本方案 §5/§8 的展开） |
| [大一统智能体平台集成方案](./大一统智能体平台集成方案.md) | 多子系统二期目标（本方案 M5） |
| [外部工具权限治理设计方案](./外部工具权限治理设计方案.md) | 工具三层权限与接入向导 |
| [IAM-RBAC方案](./IAM-RBAC方案.md) | 通用 OIDC/JWT 双轨（Jeecg 模式优先 `X-Access-Token`） |
| `AGENTS.md` / `README.md` | 运行时契约与启动方式 |

---

## 13. 一句话总结

**登录平台保持登录与权限唯一源；AI 中台认票、透传用户票、按权限裁剪工具，把「对话助手 + 业务工具 + 知识 + 审批」以页面组件与标准 API 两种方式嵌回登录平台——用户无感多一层 AI，权限与数据范围不放大。**
