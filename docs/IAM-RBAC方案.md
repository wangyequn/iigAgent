# IAM / RBAC 成熟方案调研与落地路线

> 背景：控制台「IAM / RBAC」菜单使用自研 `IamService`/`RbacGuard`，身份靠 `X-User-Id`/`X-User-Roles` 请求头明传自报，**任何调用者可伪造角色**（admin API Key 可直接放行）——上线硬伤。本报告为 2026-10-08 联网调研结论。
> 参照：AgentScope Java 2.0（https://java.agentscope.io）——SDK/Harness **无 auth 集成点**（Permission System 只管工具 ALLOW/DENY/ASK；Accounts/Namespace 仅在独立产品 AgentScope Service），故身份体系需在平台层自建。

## 结论

**推荐：Spring Security OAuth2 Resource Server（JWT）+ Keycloak OIDC 作身份源，保留现有 `IamService`/`RbacGuard` 的角色→权限点契约；API Key 仅作机器调用双轨。**

## 方案对比

| 方案 | 成熟度 | 接入成本 | 与 RbacGuard 契合 | 多租户 |
| --- | --- | --- | --- | --- |
| Spring RS + Keycloak OIDC | 高（Spring 官方标配） | 中 | 高（claims→角色映射） | 中（realm/claim） |
| Spring RS + Auth0 / Entra ID | 高 | 低-中 | 高 | 中 |
| 自研 Spring Authorization Server | 中 | 高 | 中 | 高（需自建 IdP） |
| jCasbin（RBAC with Domains） | 高（Apache 孵化） | 高 | 中（需适配层） | 高（domain 模型） |

## 落地要点（推荐方案）

1. **校验层**：`BearerTokenAuthenticationFilter` + `JwtDecoder`（配置 `issuer-uri`/`jwk-set-uri`，自动发现并轮换签名密钥）；签名/`iss`/`exp` 校验由 Spring Security 完成，**废弃 `X-User-Id`/`X-User-Roles` 自报头**。
2. **身份从 token 读**：`sub` → userId；`tenant`/`azp` claim → tenantId。`JwtAuthenticationConverter` 把 Keycloak realm/client roles 映射为 `ROLE_*`。
3. **RbacGuard 不改语义**：只换 `currentUserId`/`currentRoles` 数据源（SecurityContext 中的 JWT），`require(permission)` 闸门原样保留；现有权限点（`orch:read|write|run`、`iam:admin`、`billing:read|write`、`skill:review`、`agent:call`）映射表不变。
4. **双轨并存**：人走 JWT；服务/脚本继续 `ApiKeyAuthFilter`（`s0-demo-key`/`s0-admin-key`），Key 映射固定角色（如 admin Key → `tenant_admin`），**禁止再信外部角色头**。
5. **多租户**：租户以 token claim + API Key 绑定为准；多 realm 场景用 Spring RS Multitenancy（按 issuer 选 decoder）。更细粒度跨租户策略可二期引入 jCasbin（RBAC with Domains + JDBC adapter）。
6. **前后端**：Vue 登录走 OIDC Authorization Code + PKCE（或网关注入），`api.js` 请求带 `Authorization: Bearer`；删除手填 `X-User-*` 的输入。
7. **企业 SSO（LDAP/OA）**：Keycloak User Federation 对接 LDAP/AD 统一账号，应用只认 OIDC；OA 无 OIDC 时在 IdP 侧做 CAS/SAML 联邦。
8. **审计**：JWT 身份变更（登录/角色调整）写入 `audit_log`，与现有管理面审计同表。

## 实施步骤（建议顺序）

1. 引入 `spring-boot-starter-oauth2-resource-server`，新增 `amp.iam.issuer-uri` 配置（为空=兼容模式，仅 API Key）。
2. `JwtAuthenticationConverter` + RbacGuard 数据源切换（SecurityContext 优先，回退 headers 的兼容开关 `amp.iam.trust-headers`，默认 false）。
3. Keycloak 部署（生产 `start --optimized` + HTTPS + 备份），建 realm/client/角色，角色名与权限点映射表固化。
4. 前端登录 + Bearer 注入；删除角色头。
5. 双轨期联调：JWT 与 Key 身份优先级与审计口径对齐，旧客户端迁移窗口。

## 风险与前置

1. Keycloak 需要运维承接（HA/备份/HTTPS）；没有 IdP 运维能力前不要切默认模式。
2. 双轨期勿让 JWT 与 Key 身份混算，需明确优先级（建议 JWT > Key）与审计字段。
3. 旧客户端依赖角色头，需迁移窗口；403 语义与 `INSUFFICIENT_SCOPE` 错误体保持兼容。

## 落地状态（2026-10-09）

| 步骤 | 状态 | 说明 |
| --- | --- | --- |
| 1. Resource Server + `amp.iam.issuer-uri` | 已落地 | `platform-runtime` 引入 `spring-boot-starter-oauth2-resource-server`；`IamProperties`/`AmpSecurityConfig`；issuer/jwk 皆空=仅 API Key |
| 2. JwtConverter + RbacGuard 数据源 | 已落地 | `JwtAuthSupport`/`KeycloakRoleConverter`；`RbacGuard`/`AuthContext` JWT 优先；`amp.iam.trust-headers` 默认 false |
| 3. Keycloak 部署 | 开发骨架 | `docker-compose.keycloak.yml`（dev）；生产 HA/HTTPS/备份仍待运维 |
| 4. 前端 OIDC 登录 + Bearer | 已落地 | `auth/oidc.js` PKCE 登录/刷新/退出；`http.js` JWT 优先 Bearer；`/auth/callback`；身份抽屉 OIDC 配置；`GET /api/v1/health/iam` 发现 |
| 5. 双轨联调 / 迁移窗口 | **已联调通过** | 2026-10-09 mock OIDC（`scripts/mock-oidc-server.mjs`）+ 后端 `jwt+api-key`；API Key / JWT / RBAC 拒绝 / 伪造头验收通过；补 `UnauthorizedExceptionAdvice` 统一 403 |

## 引用

- Spring Security OAuth2 Resource Server：https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html
- JWT 校验：https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- Keycloak 配置：https://www.keycloak.org/server/configuration
- Casbin adapters：https://casbin.org/docs/adapters
- AgentScope Java Permission System：https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system
