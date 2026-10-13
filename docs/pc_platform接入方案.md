# pc_platform（Jeecg 微服务）接入 AI 中台方案

> 对接目标仓库：`D:\sinosoft\江苏二期\pc_platform`
> 形态：`jeecg-boot-master`（微服务）+ `ant-design-vue-jeecg`（前端）
> 对接方：本 AI 中台（大一统机器人 / Agent 运行时）
> 文档：2026-10 · 基于源码扫描结论

---

## 1. 目标平台画像（源码结论）

| 项 | 事实 | 对接入的影响 |
| --- | --- | --- |
| 框架 | **JeecgBoot 微服务**：`jeecg-server-cloud`（Gateway + Nacos）+ `jeecg-module-system` | AI 中台可挂同一网关/Nacos |
| 业务模块 | 当前仓库以 **system + 底座** 为主（含 OAuth2/子系统） | 业务工具接口以后续业务工程为准，接入框架先定死 |
| 登录鉴权 | **Shiro + JWT（HMAC256）**，请求头 **`X-Access-Token`** | 不是 `Authorization: Bearer` + JWKS/RSA |
| Token 内容 | `JwtUtil.sign(username, secret[, clientId])`，有效期 1h | 验签方式是 **共享密钥**，不是公钥 JWKS |
| 网关 | `GlobalAccessTokenFilter` 透传 `X-Access-Token`，并加 `X_GATEWAY_BASE_PATH` | Agent 服务应从网关收统一 token |
| 子系统 SSO | `sys/oauth2Server/getCode` → `getTokenByCode`；表 `sys_sub_system`（`client_id`） | **已有「子系统注册 + 授权码换 token」**，直接复用 |
| 权限 | Shiro `@RequiresPermissions` + **`PermissionDataAspect`（数据权限）** | 工具 `permCode` 对齐权限注解码；数据范围走现网逻辑 |
| 前端 | Vue2 + Ant Design Vue，token 存 `Vue.ls`，请求头 `X-Access-Token` | `AiBot` 组件直接读同一 token |
| 特殊开关 | `JiangSuPlatform` + `RemoteUtil.applyToken/userInfo` | 江苏省平台 OAuth 分支，接入时配置区分 |

### 1.1 认证链路（现状）

```text
用户登录 pc_platform
  → Jeecg 签发 JWT（HMAC256，X-Access-Token）
  → 前端 Vue.ls / 网关 / 业务服务（Shiro JwtFilter）

子系统 SSO（已存在）
  → GET  sys/oauth2Server/getCode?clientId=xxx   （需已登录）
  → GET  sys/oauth2Server/getTokenByCode?code&client_id&client_secret
  → 得到 Jeecg JWT（可带 clientId）
```

**结论：** 你们不需要再造一套 IdP；**pc_platform 就是身份源**。AI 中台要认的是 **Jeecg 的 `X-Access-Token`**，不是另发一套票。

---

## 2. 接入原则（与总体方案对齐）

1. **不改子系统验签规则**（尽量）：AI 侧学会认 `X-Access-Token` + Jeecg HMAC。
2. **token 透传**：Agent 调业务接口时带**同一张** `X-Access-Token`。
3. **权限子集**：工具权限码 ⊆ 该用户在 Jeecg 中的权限；数据权限仍走 `PermissionDataAspect` 语义。
4. **子系统登记**：AI 机器人在 `sys_sub_system` 注册 `client_id`，走现有 `isPermitted` 授权。
5. **未治理接口不进工具目录**（只藏按钮的接口禁止上架 Agent）。

---

## 3. 推荐架构（对接 pc_platform）

```text
ant-design-vue-jeecg 页面
  └─ <AiBot client-id="ai-bot" />     // 读 Vue.ls 的 ACCESS_TOKEN
        │  header: X-Access-Token: <Jeecg JWT>
        ▼
jeecg-cloud-gateway
  │  （可选）鉴权后转给 AI 服务
  │  header 透传 X-Access-Token
  ▼
AI 中台 Agent 服务（本仓库）
  │  ① JeecgJwtAuthFilter：验 HMAC / 或调 system 校验
  │  ② 解析 username / clientId
  │  ③ 拉 Nacos 工具目录 → 权限裁剪
  │  ④ 调业务工具：透传 X-Access-Token
  ▼
业务微服务（Shiro JwtFilter 原样校验）
  └─ 业务校验 + PermissionDataAspect 数据权限
```

### 3.1 鉴权两种落地方式（二选一，推荐 A）

| 方式 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| **A. 共享密钥验签（推荐）** | AI 配置与 Jeecg 相同的 JWT secret，本地验 `X-Access-Token` | 低延迟、无每请求 RPC | 需安全共享 HMAC 密钥 |
| **B. 调 system 校验** | 调用 Jeecg 用户接口/自省接口确认 token | 密钥不共享 | 每请求/缓存 RPC；需开放只读校验口 |

> 说明：Jeecg 为 **HMAC 对称签名**，没有 JWKS 公钥。所谓「多 issuer」在本平台退化为「**多 client_id + 同一 HMAC 密钥（或多个 secret 配置）**」。若未来多子系统各自 HMAC 密钥，则在信任表按 `client_id` 配 secret。

### 3.2 信任注册表（映射到 Jeecg 既有表）

| AI 侧配置 | Jeecg 侧对应 |
| --- | --- |
| `clientId` | `sys_sub_system.client_id` |
| 验签 secret | JwtUtil 使用的 secret（配置中心） |
| 工具白名单 | AI 工具目录（Nacos）`tools` 字段 |
| 用户授权 | `sys_sub_system_role`（角色能否进该子系统） |

---

## 4. 机器人嵌入 pc_platform 前端

### 4.1 位置

在 `ant-design-vue-jeecg` 全局布局（如 `BasicLayout` / 全局组件）挂载：

```vue
<AiBot
  :token="accessToken"
  client-id="ai-bot"
  :user-id="userId"
  :org-id="orgId"
/>
```

- `accessToken`：`Vue.ls.get(ACCESS_TOKEN)`（与业务请求一致）
- 默认 **左下角** 悬浮；主题色随 `pc_platform`
- **禁止**在前端写 AI 平台 API Key

### 4.2 调用

```http
POST {aiBase}/api/v1/agents/{agentId}/messages:stream
X-Access-Token: <Jeecg JWT>
X-Client-Id: ai-bot
```

AI 侧兼容：优先读 `X-Access-Token`，否则 `Authorization: Bearer`（平台双轨）。

---

## 5. Token 与权限如何打通

### 5.1 AI 侧需要从 token/上下文取到

| 字段 | 来源 |
| --- | --- |
| `username` / `userId` | Jwt claim / LoginUser |
| `clientId` | Jwt claim（`JwtUtil.sign(..., clientId)`）或请求头 |
| 角色/权限码 | 调 system：`/sys/permission/getUserPermissionByToken` 类接口（或缓存） |
| 部门/数据范围 | `LoginUser` / 数据权限上下文 |

### 5.2 工具权限子集

```text
visible_tools = catalog.tools.filter(t =>
    userPerms.includes(t.permCode) && t.enabled && t.scope !== forbidden
)
```

- `permCode` 与 Jeecg **`@RequiresPermissions("xxx:yyy")` 一致**
- 调用前再校验一次；写操作 `scope=write` 默认 HITL
- 查询自动带上数据权限参数（deptId / 创建人），与 `PermissionDataAspect` 口径一致

### 5.3 不放大权限

- Agent **只透传用户 token**，禁止使用管理员/系统账号调业务
- 若必须代理：仅限已登记 `client_id` + 服务端强制注入 userId 并审计

---

## 6. 工具接口目录（对接业务工程）

### 6.1 扫描来源

1. 各业务服务 **Swagger**（`Swagger2Config` 已开 `X-Access-Token`）
2. 网关路由表（Nacos）
3. 人工补全：功能描述、权限码、读写、风险

### 6.2 注册到 Nacos（AI 侧）

```yaml
# dataId: tools-pc-platform.yaml  group: AMP_TOOLS
system: pc-platform
clientId: ai-bot
authHeader: X-Access-Token
tools:
  - intent: user.query
    name: 查询用户
    method: GET
    path: /sys/user/list
    permCode: "user:query"       # 对齐 Jeecg 权限
    scope: read
    risk: low
    enabled: true
```

### 6.3 一期建议只读工具（示例，以实际业务模块为准）

| intent | 典型接口 | 权限 |
| --- | --- | --- |
| 用户/组织查询 | `/sys/user` `/sys/depart` | 只读 |
| 字典/配置查询 | `/sys/dict` | 只读 |
| 业务查询类 | 各模块 list/page | 只读 |
| 审批/删除/导出 | — | **默认不上架或 HITL** |

---

## 7. 在 pc_platform 侧要做的事（尽量少改）

| 改动 | 必要性 | 说明 |
| --- | --- | --- |
| `sys_sub_system` 插入 `ai-bot` | 必须 | 让 `getCode`/`isPermitted` 认识机器人 |
| 角色绑定 `sys_sub_system_role` | 必须 | 控制哪些角色能用机器人 |
| JWT secret 同步给 AI 配置 | 必须（方式 A） | 配置中心/密钥管理，勿进 Git |
| 只读「token 校验/用户信息」接口 | 方式 B 需要 | 如 `/sys/user/userInfo` 已存在可复用 |
| 网关路由 → AI 服务 | 推荐 | 与业务服务同入口，统一域名 |
| 业务权限码文档/Swagger | 推荐 | 便于目录生成 `permCode` |

**业务微服务代码可不改**（继续收 `X-Access-Token`）。

---

## 8. 与「换票 / 多 issuer」讨论的对应关系

| 通用方案 | 在 pc_platform 的落地 |
| --- | --- |
| 多 issuer + JWKS | **退化为**：HMAC secret（按 client/环境）+ `X-Access-Token` |
| token 透传 | **成立**：Jeecg JWT 打回 Jeecg 业务服务 |
| 换票中心 | **不需要**（已有 `getTokenByCode` 可作可选增强） |
| 可选增强 | 挂到 `sys/oauth2Server` 做标准 code 换发（AI 作为 `client_id`） |

若以后要接 **非 Jeecg** 子系统，再启用通用「信任注册表 + JWKS」；pc_platform 走 Jeecg 适配器即可。

---

## 9. 实施步骤（建议）

### M1 身份（0.5～1 周）

1. `sys_sub_system` 注册 `ai-bot`，角色绑定  
2. AI 增加 `JeecgTokenAuthFilter`（读 `X-Access-Token`，HMAC 验签）  
3. 联调：前端带 token 调 Agent → 解析出用户  

**验收：** 无 token 401；伪 token 401；合法 token 能拿到 username。

### M2 透传只读工具（1 周）

1. 选 2～3 个只读接口进 Nacos 目录  
2. Agent 工具调用透传 `X-Access-Token`  
3. 用不同角色用户验证「无权限 403 / 数据范围正确」  

**验收：** 越权用户不能看到或调用无权限工具。

### M3 组件挂载（3～5 天）

1. `AiBot` 打进 `ant-design-vue-jeecg`  
2. 读 `Vue.ls` token，左下角悬浮  
3. 流式对话 + 审批弹层  

**验收：** 登录用户在业务页直接问「查我的订单」类只读问题。

### M4 目录与权限（1～2 周）

1. Swagger 扫描脚本 → 意图草稿  
2. 权限码对齐 `@RequiresPermissions`  
3. 高危 write 默认关闭 / HITL  
4. 审计：username、clientId、tool、params  

### M5 大一统路由（后续）

意图 → 多领域 Agent；跨业务工具组合；管理台目录审核。

---

## 10. 配置草图（AI 中台）

```yaml
amp:
  jeecg:
    enabled: true
    token-header: X-Access-Token
    # 方式 A：与 pc_platform JwtUtil 相同 HMAC secret
    jwt-secret: ${JEECG_JWT_SECRET:}
    # 方式 B：校验/用户信息（可选）
    system-base-url: http://jeecg-system
    user-info-path: /sys/user/getUserInfo
    client-id: ai-bot
```

---

## 11. 风险与对策

| 风险 | 对策 |
| --- | --- |
| HMAC secret 泄露 | 密钥托管、轮换；优先网关侧验签后注入可信头 |
| Token 仅 1h | 组件静默刷新/失败引导重新登录 |
| 权限码与按钮不一致 | 目录以 `@RequiresPermissions` 为准，管理台人工确认 |
| 只藏按钮的接口 | 未治理禁止进目录 |
| `JiangSuPlatform` 分支差异 | 环境开关进信任表；联调覆盖两分支 |
| 数据权限被绕过 | 工具层传数据范围参数 + 业务 SQL 强制；禁止无过滤 list |

---

## 12. 验收清单（针对 pc_platform）

- [ ] `sys_sub_system` 含 `ai-bot`，无授权角色调用被拒  
- [ ] `X-Access-Token` 合法/非法/过期行为正确  
- [ ] Agent 调业务接口与页面手工调用**同等鉴权结果**  
- [ ] 无 `user:query` 权限用户看不到用户查询工具  
- [ ] 数据范围：仅本部门可见  
- [ ] 审计可按 username 检索  
- [ ] 前端仅使用 `Vue.ls` token，无平台密钥  

---

## 13. 一句话总结

**pc_platform 已是身份与 OAuth2 子系统中心；AI 中台作为「子系统 + 微服务」注册 `client_id`，识别 `X-Access-Token`（Jeecg HMAC JWT），工具调用原 token 透传——权限与数据权限继续由 Jeecg 拦截器生效，机器人只做编排与问答。**
