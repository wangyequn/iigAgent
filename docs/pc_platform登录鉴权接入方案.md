# pc_platform 登录与鉴权接入方案

> **目标：** AI 中台（本项目）的**登录认证 + 权限鉴权**由 **pc_platform（Jeecg 微服务权限平台）** 统一负责；AI 中台只做智能体业务，不自建账号体系。
>
> **不在本文范围：** 把机器人挂到 pc_platform 页面（那是后续集成形态，不是本次「登录/鉴权」）。

---

## 1. 问题澄清

| 误解 | 正确理解 |
| --- | --- |
| 在 AI 中台再做一套登录 | **不做**；账号、角色、组织都在 pc_platform |
| 只用 client_id 放行 | client_id 只是「应用标识」；**验签/校验 token** 必须走平台 |
| AI 自己签发用户 token | **不做**；用户票由 pc_platform 签发 |
| 机器人权限单独配一套 | 机器人可用权限 ⊆ **pc_platform 里该用户的权限** |

一句话：**人到 pc_platform 登录；AI 中台认这张票，并按票里的用户去 pc_platform 换取/使用权限。**

---

## 2. 对 pc_platform 的源码事实（接入约束）

| 项 | 事实 |
| --- | --- |
| 鉴权组件 | Shiro + JWT（**HMAC256**） |
| 请求头 | **`X-Access-Token`**（不是标准 `Authorization: Bearer`） |
| JWT 声明 | `username`、`clientId`（可选）、`exp` |
| 签名密钥 | `JwtUtil.sign(username, 密码, clientId)`——**与用户密码有关**，外部**不宜自算密钥验签** |
| SSO | `sys/oauth2Server/getCode` → `getTokenByCode`（子系统 `client_id` + `client_secret`） |
| 用户接口 | `/sys/login/loginByToken`、`/sys/user/getUserInfo` 等 |
| 权限 | 角色 + 资源权限码（`@RequiresPermissions`）+ **数据权限**（`PermissionDataAspect`） |

因此：**AI 中台「接入登录」= 认 `X-Access-Token`；「接入鉴权」= 用 pc_platform 的用户/角色/权限接口。**

---

## 3. 总体接入架构

```text
┌─────────────────────────────────────────────────────────────┐
│  用户                                                        │
│   │ 打开 AI 中台（或业务系统已登录后跳转）                    │
│   ▼                                                          │
│  pc_platform 登录页 / 已有会话                               │
│   │ 登录成功                                                │
│   ▼                                                          │
│  签发 JWT → X-Access-Token                                  │
└───────────────────┬─────────────────────────────────────────┘
                    │
        ┌───────────▼───────────┐
        │ AI 中台 前端 / 网关     │  持有并透传 X-Access-Token
        └───────────┬───────────┘
                    │
        ┌───────────▼───────────┐
        │ AI 中台 服务            │
        │ ① 认票（校验 token）   │
        │ ② 取人（username 等）  │
        │ ③ 取权（角色/权限码）  │
        │ ④ 业务 + Agent 调用    │
        └───────────┬───────────┘
                    │ 需要用户/权限时
        ┌───────────▼───────────┐
        │ pc_platform           │
        │ 用户/角色/权限/组织    │
        └───────────────────────┘
```

### 3.1 AI 中台在链路中的职责

| 做 | 不做 |
| --- | --- |
| 校验平台发的 token | 不签发用户登录 token |
| 把 token 里的用户当作当前用户 | 不用管理员账号代替用户 |
| 向平台查角色/权限码/组织 | 不在本地维护另一套用户表（可缓存） |
| 工具/API 调用前做权限子集检查 | 不绕过 pc_platform 权限 |

---

## 4. 登录怎么接（两种模式，推荐 4.2）

### 4.1 模式 A：前端持票直调（简单，推荐先做）

适用于：用户已在 pc_platform（或统一门户）登录，页面/网关把票带给 AI 中台。

```text
1) 用户在 pc_platform 登录 → 得到 X-Access-Token
2) 打开 AI 中台前端（或嵌入 iframe / 门户菜单）
3) 前端所有 AI 接口带：X-Access-Token: <token>
4) AI 后端校验 token → 进入业务
```

- **优点：** 实现量小；与 Jeecg 前端习惯一致（`Vue.ls` / `X-Access-Token`）。
- **前提：** token 能在 AI 域名下使用（网关/网关 Cookie / 门户带票跳转），注意 CORS 与有效期（约 1 小时）。

### 4.2 模式 B：OAuth2 授权码（更正规，推荐目标态）

pc_platform 已有子系统 OAuth2（`getCode` / `getTokenByCode`），把 **AI 中台注册成一个 client**：

```text
1) 用户访问 AI 中台，未登录
2) 跳转 pc_platform：/sys/oauth2Server/getCode?clientId=ai-bot
   （用户已登录则直接授权；未登录则先登录）
3) 回调 AI 中台：?code=xxx
4) AI 后端（或前端）用 code + client_id + client_secret
   调 getTokenByCode → 换成 X-Access-Token（Jeecg JWT）
5) 后续请求带该 token
```

- **优点：** 标准「登录中心 → 应用」模型；权限校验仍用用户票。
- **前提：** 在 pc_platform 的 `sys_sub_system` 注册 `ai-bot`，配置 `client_secret`；确认回调地址。

### 4.3 AI 中台对外统一规则

| 请求 | 认什么 |
| --- | --- |
| 浏览器/页面调用 | **必须**带 `X-Access-Token`（模式 A 或 B 得到） |
| 服务间/脚本（可选保留） | 可继续用平台 API Key（机器轨）；**与用户权限隔离** |

**用户身份优先：有 `X-Access-Token` 且校验通过 → 当前用户即 token 用户，禁止再回落到「系统管理员 Key」去查业务数据。**

---

## 5. 鉴权怎么接（三层）

### 5.1 第一层：认票（Token 合法性）

目标：确认「这张票是 pc_platform 发的、未过期、用户仍有效」。

**不要**用「只解析 JWT payload 不验签」在生产放行（可被伪造）。

可选实现（推荐顺序）：

| 方案 | 做法 | 建议 |
| --- | --- | --- |
| **B1. 远程校验（推荐）** | 调 pc_platform：`loginByToken` 或 `getUserInfo`（带原 token） | 与登录态一致；实现简单 |
| **B2. 平台校验接口** | 若有「token 自省/校验」专用 API，优先用 | 语义最清晰 |
| **B3. 同密钥验签** | 仅当你们约定**全局 HMAC 密钥**且可安全下发 | Jeecg 默认用用户密码作密钥，**通常不适用** |

> 我方仓库里曾出现「本地 decode + 远程校验」草稿，**方向对但必须以远程校验为准**；未配置 `baseUrl` 时生产应拒绝，而不是只 decode。

### 5.2 第二层：取人（当前用户）

token 解析/校验后至少得到：

| 字段 | 来源 |
| --- | --- |
| `username` / `userId` | JWT claim / 用户接口 |
| `clientId` | JWT claim（可选） |
| 部门 / 组织 | 用户接口或 `getUserInfo` |
| 租户（若多租户） | 平台约定 claim 或配置默认租户 |

写入 AI 中台请求上下文，供会话、审计、Agent 使用。

### 5.3 第三层：取权（能不能做）

| 权限 | 来源 | AI 侧用法 |
| --- | --- | --- |
| 功能权限码 | pc_platform 用户权限（角色→资源） | 管理 API、工具是否允许 |
| 菜单/按钮 | 同一权限码体系 | 与前端隐藏无关，**服务端必须再判** |
| 数据权限 | 组织范围（本人/本部门/自定义） | 查询过滤、Agent 工具入参约束 |

```text
AI 接口/工具调用
  = token 校验通过
  ∧ 权限码 ∈ 用户在 pc_platform 的权限
  ∧ 数据范围允许
  ∧（可选）Agent 工具白名单
```

**实现建议：**

1. AI 中台提供内部服务：`PermissionClient.queryPerms(username)`（调 pc_platform，带缓存 TTL）。  
2. 拦截器：每个 `/api/**` 解析 token → 注入 UserContext。  
3. 注解或代码级：`@RequirePermission("order:query")` → 查缓存/平台。  
4. Agent 工具目录里的 `permCode` 与 pc_platform **同一套码**（与 `@RequiresPermissions` 对齐）。

---

## 6. 接口依赖（pc_platform 需提供/确认）

| 用途 | 建议接口 | 说明 |
| --- | --- | --- |
| 登录 | `/sys/login/login` | 密码/验证码登录（若 AI 自带登录页可代理） |
| SSO | `/sys/oauth2Server/getCode`、`getTokenByToken` 等 | 模式 B |
| 认票 | `/sys/login/loginByToken` 或 `/sys/user/getUserInfo` | **必须**；带原 `X-Access-Token` |
| 用户信息 | 用户/部门/角色查询 | 取人 + 数据范围 |
| 权限码 | 用户权限集合（或角色权限） | 第三层鉴权 |
| （可选）登出 | 注销 token | 安全需要 |

请业务确认上述接口的**准确路径、入参、返回 JSON**；若与标准 Jeecg 不一致，以 pc_platform 实现为准。

---

## 7. 时序（推荐目标态：模式 B + 远程认票）

```text
用户                浏览器(AI前端)           AI服务                pc_platform
 │ 点登录/打开AI        │                      │                      │
 │────────────────────▶│  跳转 getCode        │                      │
 │                      │─────────────────────────────────────────────▶
 │ 登录/授权            │◀── code ────────────────────────────────────
 │                      │  回调 /callback?code                        │
 │                      │─────────────────────▶│                      │
 │                      │                      │ getTokenByCode(code) │
 │                      │                      │─────────────────────▶
 │                      │                      │◀── X-Access-Token ───
 │                      │◀── Set 存 token ─────│                      │
 │ 调用业务             │  X-Access-Token      │                      │
 │─────────────────────▶│─────────────────────▶│ 认票(getUserInfo)    │
 │                      │                      │─────────────────────▶
 │                      │                      │◀── 用户/有效 ────────
 │                      │                      │ 查权限码(缓存)        │
 │                      │                      │─────────────────────▶
 │                      │                      │ 业务/Agent          │
```

模式 A 则去掉 code 换票，前端直接带已有 `X-Access-Token`。

---

## 8. 与本仓库现状如何对齐

| 本仓库已有 | 对齐方式 |
| --- | --- |
| API Key 双轨 | 保留给**脚本/机器**；**用户请求以 Jeecg token 为准** |
| OIDC/JWT（Keycloak） | 可继续存在；**pc_platform 模式下优先 `X-Access-Token`** |
| RbacGuard 权限点 | 数据源改为「pc_platform 权限码」或映射表 |
| 审计 | 记录 token 中的 `username`，而非仅 API Key 尾号 |
| 前端 | 登录后保存 `X-Access-Token`，请求头统一注入 |

**身份优先级（建议写死）：**

```text
X-Access-Token（pc_platform） > Authorization: Bearer（OIDC JWT） > Authorization: Bearer（API Key）
```

---

## 9. 安全要求（上线门禁）

1. **生产禁止**「仅 decode 不验签」。  
2. token 校验失败 → **401**，不回落到 Key。  
3. 权限码与数据权限必须在**服务端**执行，前端隐藏无效。  
4. `client_secret`、平台密钥只放服务端配置。  
5. token 有效期与 pc_platform 一致；过期引导重新登录。  
6. 跨域：AI 与 pc_platform 同网关或明确 CORS/网关带票方案。  

---

## 10. 实施步骤

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **P0 接口对齐** | 确认认票/用户/权限/SSO 接口与 JSON | 文档与样例齐全 |
| **P1 认票 + 取人** | `X-Access-Token` 过滤器 + 远程校验 + UserContext | 合法票 200；伪票/过期 401 |
| **P2 权限** | 权限码拉取/缓存 + 注解/拦截鉴权 | 无权限 403；与 pc_platform 一致 |
| **P3 登录形态** | 模式 A 门户带票 或 模式 B OAuth2 | 用户无感进 AI，无二次账号 |
| **P4 数据权限** | 组织范围进查询/Agent | 跨部门不可见 |
| **P5 收敛** | 机器 Key 仅运维；审计对齐 | 用户行为可追到 username |

---

## 11. 已确认决策 + 联调实测（2026-10）

| 项 | 决策 / 实测 |
| --- | --- |
| 登录 | 模式 B：`getCode` → `getTokenByCode`；登录 `POST /sys/login` 需 captcha + **clientId** |
| 认票 | **`POST /sys/loginByToken`** body `{"token":"..."}`（**不是** `/sys/login/loginByToken`） |
| 部署 | 同网关 **`http://127.0.0.1:9999`**，同域 |
| 权限 | `GET /sys/permission/getUserPermissionByToken`；**`auth` 可能为空，用 `allAuth[].action`**（实测 1938 条） |
| JWT 声明 | `username`、`clientId`、`exp`（HMAC） |

### 对应实现

- `amp.jeecg.*` / `JeecgAuthClient` / `JeecgAuthFilter` / `JeecgAuthController`
- `RbacGuard` 按权限码放行  

---

## 12. 一句话结论

**pc_platform 是唯一的登录与权限中心：AI 中台只认它发的 `X-Access-Token`，用它的接口完成「认票 → 取人 → 取权」，再跑 Agent 业务；不在 AI 侧另建账号，也不在未验签的情况下放行。**
