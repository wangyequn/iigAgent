# 登录平台 × AI 中台 联调说明（开工版）

> 配套：`docs/登录平台接入AI-Agent方案.md` · `docs/登录平台模块与工具清单.md`  
> 本篇只写「怎么把已落地的东西跑起来」。

---

## 1. 本次落地内容

| 项 | 位置 | 说明 |
| --- | --- | --- |
| `biz-assist` 播种 | `CatalogBootstrap.seedBizAssist` | Agent + 系统提示词 + 只读权限；`amp.pc-platform.base-url` 非空时登记 8 个 HTTP 工具 |
| 首批工具 | `query_my_info` 等 8 个 | `authMode=user_token`，调用透传 `X-Access-Token` |
| 路径参数 | `HttpBusinessTool` | URL 支持 `{dictCode}` 等模板 |
| 配置 | `application.yml` → `amp.pc-platform.base-url` | env `AMP_PC_PLATFORM_BASE_URL` |
| 注册脚本 | `scripts/register-pc-platform-tools.ps1` | 运行时经 Admin API 登记/授权 |
| 前端组件 | `scripts/pc-platform-aibot/AiBot.vue` + `MOUNT.md` | Vue2 悬浮助手 |

---

## 2. 前置

```text
Redis :6379
PG    :15432（amp/amp）
AI    mvn -DskipTests install → scripts/run-api-18081.cmd
登录平台  pc_platform 可访问（网关或 system 服务）
```

密钥：`AMP_ADMIN_API_KEY`（默认种子 `s0-admin-key`，生产请轮换）。

---

## 3. 两条登记路径（二选一）

### A. 启动播种（推荐本地）

```powershell
$env:AMP_PC_PLATFORM_BASE_URL = "http://127.0.0.1:8080"   # 按实际登录平台地址
$env:AMP_JEECG_BASE_URL = "http://127.0.0.1:8080"
$env:AMP_JEECG_AUTH_ENABLED = "true"
mvn -pl platform-open-api -am spring-boot:run
```

启动日志应出现：`seed biz-assist` / `seed biz-assist http tool`。

### B. 脚本热登记

```powershell
$env:AMP_BASE = "http://127.0.0.1:18081"
$env:AMP_ADMIN_KEY = "s0-admin-key"
$env:JEECG_BASE = "http://127.0.0.1:8080"
powershell -File scripts/register-pc-platform-tools.ps1
```

---

## 4. 登录平台侧最小动作

1. `sys_sub_system` 增加 `client_id=ai-bot`（及 `sys_sub_system_role` 授权角色）  
2. 确认：`POST /sys/loginByToken`、`GET /sys/user/getUserInfo`、`GET /sys/permission/getUserPermissionByToken`  
3. 前端按 `scripts/pc-platform-aibot/MOUNT.md` 挂 `AiBot`

---

## 5. 验收清单

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 带合法 `X-Access-Token` 调 `messages:stream` | SSE 流式回复 |
| 2 | 无票 / 伪票 | 401 `INVALID_JEECG_TOKEN`（或 API Key 拒绝） |
| 3 | 提问「查我的信息」 | 调 `query_my_info`，内容与登录平台一致 |
| 4 | 无 `system:user:query` 用户 | 不应稳定拿到他人用户列表（业务 403/空） |
| 5 | `query_dict` 传 `dictCode` | 路径替换正确，返回字典项 |
| 6 | 前端无硬编码平台 Key | 仅 `Vue.ls` token |

---

## 6. 调用样例

```bash
curl -N -X POST http://127.0.0.1:18081/api/v1/agents/biz-assist/messages:stream \
  -H "X-Access-Token: <Jeecg JWT>" \
  -H "Content-Type: application/json" \
  -d '{"userId":"zhangsan","sessionId":"sess_001","message":"查我的信息"}'
```

---

## 7. 下一步

- [ ] 按真实 `permCode` 校准工具 `permCode` 字段  
- [ ] 写类工具二期：ASK + HITL  
- [ ] `knowledge-qa` 知识 Agent 播种  
- [ ] 网关同域路由 + CORS  
