<div align="center">

# iigAgent · 智能体应用平台

**基于 AgentScope Java 的企业级 AI 中台**  
多租户 · 权限治理 · 人机协同 HITL · 知识库 RAG · 编排与观测

[![CI](https://img.shields.io/badge/CI-GitHub_Actions-2ea44f)](.github/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.5-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![AgentScope](https://img.shields.io/badge/AgentScope-Java_2.0.3-3178C6)](https://java.agentscope.io/)
[![Vue 3](https://img.shields.io/badge/Frontend-Vue3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)

[快速开始](#-快速开始) · [架构](#-系统架构) · [能力](#-核心能力) · [文档](#-文档) · [参与贡献](#-参与贡献)

</div>

---

## 📖 项目简介

**iigAgent**（AI Intelligent Agent Platform）是一套可落地的企业智能体应用平台。在 [AgentScope Java](https://java.agentscope.io/) 运行时之上，补齐了多租户鉴权、Agent 目录与版本发布、工具权限治理、人在回路（HITL）、知识库检索、观测审计与管理控制台等生产级能力。

平台已跑通 **S0–S9** 主干能力，适合作为：

- 企业内部 AI 助手 / 业务中台的底座
- 多 Agent 编排与工具调用的治理层
- 需要审批、审计、配额的生产级对话服务

> 架构与路线图详见 [`docs/AI中台设计文档.md`](docs/AI中台设计文档.md)。

---

## ✨ 核心能力

| 能力域 | 说明 |
| --- | --- |
| **对话与会话** | 多轮对话、SSE 流式、中断 / 恢复 / 清上下文，会话状态存 Redis |
| **Agent 目录** | 版本化管理、发布指针切流、旧版本 drain 下线，懒加载实例缓存 |
| **权限与 HITL** | 工具 ALLOW / ASK / DENY，危险操作人工审批，一键「不再询问」 |
| **工具生态** | 业务 HTTP 工具、MCP 服务器、沙箱执行、按 Agent 绑定与授权 |
| **知识库 RAG** | 文档入库 / 检索、可插拔 Embedding，可对接 Dify Dataset API |
| **鉴权** | API Key + Scope；JWT / OIDC（Keycloak）；Jeecg 平台登录对接 |
| **编排与渠道** | Workflow（含 HUMAN_GATE）、飞书等 Channel、外部 Agent 转发 |
| **观测与治理** | 审计日志、配额计量、限流、Prometheus / OTel / Loki / Nacos |
| **长期记忆** | 可选 Mem0 扩展，支持自动召回与沉淀 |
| **管理控制台** | Vue3 控制台：对话、治理、知识、工具、运维一屏管理 |

---

## 🏗 系统架构

```text
┌─────────────────────────────────────────────────────────────────┐
│                     platform-console-ui (Vue3)                  │
│          对话 · Agent 管理 · 权限 · 知识库 · 工具 · 运维            │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTP / SSE (Bearer / JWT)
┌───────────────────────────────▼─────────────────────────────────┐
│                    platform-open-api (Spring Boot)              │
│     Web Controllers · 装配 · ConsoleBot · OpenAI 兼容端点         │
└───┬───────────┬───────────┬───────────┬───────────┬─────────────┘
    │           │           │           │           │
┌───▼───┐  ┌────▼────┐  ┌───▼────┐  ┌───▼───┐  ┌───▼──────────┐
│runtime│  │governance│  │  ops   │  │channel│  │  amp-starter │
│会话/目录│  │权限/技能 │  │观测/配置│  │编排/IAM│  │  金路径样例   │
│知识/模型│  │ MCP/沙箱 │  │审计/限流│  │渠道自动化│ └──────────────┘
└───┬───┘  └────┬────┘  └───┬────┘  └───┬───┘
    │           │           │           │
┌───▼───────────▼───────────▼───────────▼─────────────────────────┐
│              AgentScope Java Runtime (Harness)                  │
│         模型调用 · 工具执行 · 中间件 · 会话 State                  │
└───┬─────────────────────────┬─────────────────────────┬─────────┘
    │                         │                         │
┌───▼────────┐        ┌───────▼───────┐         ┌───────▼───────┐
│ PostgreSQL │        │     Redis     │         │  LLM Provider │
│ 目录/治理表  │        │  会话 State    │         │ DeepSeek/…    │
└────────────┘        └───────────────┘         └───────────────┘
```

模块依赖方向：

```text
platform-common → platform-runtime → platform-governance
       → platform-ops → platform-channel → platform-open-api

amp-starter（金路径样例，Copy 模式，不依赖 platform-*）
platform-console-ui（Vue3 控制台，不在 Maven reactor 内）
```

| 模块 | 职责 |
| --- | --- |
| `platform-common` | 公共 DTO（如 `ApiError`） |
| `platform-runtime` | 目录 / 鉴权 / 会话 / Agent 缓存 / 知识 / 模型 / FS / 计量 |
| `platform-governance` | 工具权限 / MCP / 技能 / 沙箱 |
| `platform-ops` | Nacos / OTel / Loki / 限流 / 审计 |
| `platform-channel` | Channel / 编排 / IAM-RBAC / Automation |
| `platform-open-api` | Spring Boot 入口 + 控制器 + 装配 |
| `amp-starter` | 最小可运行金路径（可直接抄） |
| `platform-console-ui` | 管理控制台 |

---

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | **21+** |
| Maven | 3.8+ |
| PostgreSQL | 目录与治理表（默认 `127.0.0.1:15432/amp`） |
| Redis | Agent 会话 State（默认 `127.0.0.1:6379`） |
| 模型密钥 | `DEEPSEEK_API_KEY`（默认），或 DashScope / OpenAI |
| Node.js | 18+（仅控制台需要） |

### 1. 启动依赖（Docker）

```bash
# PostgreSQL（宿主机 15432）
docker run -d --name amp-pg -p 15432:5432 \
  -e POSTGRES_DB=amp -e POSTGRES_USER=amp -e POSTGRES_PASSWORD=amp \
  postgres:16-alpine

# 本机若无 Redis，可再起：
docker run -d --name amp-redis -p 16379:6379 redis:7-alpine
# export AMP_REDIS_URI=redis://127.0.0.1:16379
```

### 2. 配置模型密钥

```bash
export DEEPSEEK_API_KEY="sk-..."
# Windows PowerShell:
# $env:DEEPSEEK_API_KEY = "sk-..."
```

密钥请写入本机环境变量或 gitignore 的 `scripts/local-env.*`，**不要写进仓库**。

### 3. 构建并启动后端

```bash
# 先 install 模块依赖（模块化后必需）
mvn -B -DskipTests install

# 启动（默认 http://127.0.0.1:18081）
mvn -pl platform-open-api spring-boot:run
# 或：scripts/run-api-18081.cmd
```

### 4. 启动控制台（可选）

```bash
cd platform-console-ui
npm install
npm run dev
# 浏览器打开 http://127.0.0.1:5173
# 默认 API Key: s0-demo-key · Agent: demo
```

### 5. 验证

```bash
# 健康检查（免鉴权）
curl http://127.0.0.1:18081/api/v1/health/demo

# 业务对话（需 open scope）
curl -s -X POST http://127.0.0.1:18081/api/v1/agents/demo/messages \
  -H "Authorization: Bearer s0-demo-key" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","sessionId":"demo-1","message":"你好"}'
```

一键全容器：

```bash
export DEEPSEEK_API_KEY="sk-..."
docker compose up --build
# 宿主机 http://127.0.0.1:18081
```

### 默认 API Key

| Key | Scope | 用途 |
| --- | --- | --- |
| `s0-demo-key` | `open` | 业务对话 / 会话接口 |
| `s0-admin-key` | `open, admin` | 管理台 / 审计 / 配额 |

> `tenantId` 以 Key 为准。生产环境请轮换默认 Key，并关闭明文演示配置。

---

## 🔌 API 速览

除 `/api/v1/health/**` 外，均需：

```http
Authorization: Bearer <api-key>
```

| 类别 | 端点示例 |
| --- | --- |
| 对话 | `POST /api/v1/agents/{id}/messages` |
| 流式 | `POST /api/v1/agents/{id}/messages:stream` |
| 会话 | `history` / `interrupt` / `confirm` / `clear-context` |
| OpenAI 兼容 | `POST /api/v1/chat/completions`（`model`=agentId） |
| 管理面 | `GET/POST /api/v1/admin/**`（目录、权限、工具、审计…） |

约定要点：

- 流式路径使用**冒号** `messages:stream`（非 `/stream`）
- 会话接口强制 `userId` + `sessionId`（`[A-Za-z0-9_-]{1,128}`）
- Open Key 调用 admin → `403 INSUFFICIENT_SCOPE`
- 危险工具 ASK 时：`409 HITL_CONFIRM_REQUIRED`，审批经 `POST .../confirm` 恢复

更多接口与易踩坑说明见 [AGENTS.md](AGENTS.md) 与 [docs](docs/)。

---

## 🗂 项目结构

```text
.
├── platform-common/          # 公共类型
├── platform-runtime/         # 运行时核心
├── platform-governance/      # 权限 / 技能 / MCP
├── platform-ops/             # 观测与配置
├── platform-channel/         # 渠道 / 编排 / IAM
├── platform-open-api/        # Spring Boot 入口
│   └── src/main/resources/db/schema.sql
├── platform-console-ui/      # Vue3 管理控制台
├── amp-starter/              # 金路径最小样例
├── scripts/                  # 启动 / 冒烟 / 运维脚本
├── docs/                     # 架构与方案文档
├── docker-compose.yml
└── .github/workflows/ci.yml  # mvn -B -q verify
```

---

## 🧪 开发与测试

```bash
# 全量构建 + 单测（与 CI 一致）
mvn -B verify

# 单个测试类
mvn -pl platform-open-api -Dtest=ChatHttpContractTest test

# 铺默认 Agent（demo / console-bot / biz-assist，幂等）
python scripts/provision_agents.py

# 冒烟 / 自检
powershell -File scripts/n4-check.ps1
powershell -File scripts/smoke-load.ps1 -N 20
```

单元测试不依赖 Redis / PostgreSQL / 模型密钥，`mvn -B verify` 即可本地与 CI 对齐。

---

## 📚 文档

| 文档 | 内容 |
| --- | --- |
| [AI 中台设计文档](docs/AI中台设计文档.md) | 总体架构与能力路线 |
| [项目总体架构总结](docs/项目总体架构总结.md) | 架构梳理 |
| [源码导读](docs/源码导读.md) | 代码入口与调用链 |
| [IAM-RBAC 方案](docs/IAM-RBAC方案.md) | JWT / OIDC / RBAC |
| [Nacos AI Registry](docs/Nacos-AI-Registry接入方案.md) | MCP / AgentCard / Prompt |
| [amp-starter 与模块化](docs/方案-amp-starter与Maven模块化.md) | 金路径样例说明 |
| [二期项目开发文档](docs/二期项目开发文档.md) | 二期能力说明 |
| [AGENTS.md](AGENTS.md) | 开发约定、命令与易踩坑（推荐先读） |

---

## 🗺 能力路线（S0–S9）

| 阶段 | 主题 | 状态 |
| --- | --- | --- |
| S0 | Walking Skeleton · 对话 / 流式 / 鉴权骨架 | ✅ |
| S1 | API Key · 会话五接口 | ✅ |
| S2 | 控制台 Playground | ✅ |
| S3 | 目录版本 · 发布切流 | ✅ |
| S4 | HITL · 工具权限 · MCP | ✅ |
| S5 | 技能市场与灰度 | ✅ |
| S6 | 知识库 RAG | ✅ |
| S7 | 观测 / 限流 / 审计 / 配额 | ✅ |
| S8 | 多副本 · SSE 续传 · External | ✅ |
| S9 | 计量计费 · 编排 · 渠道 | ✅ |

---

## 🤝 参与贡献

欢迎 Issue 与 Pull Request。

1. Fork 本仓库并创建特性分支：`git checkout -b feature/xxx`
2. 保证 `mvn -B verify` 通过
3. 提交清晰的 commit message（说明动机与影响面）
4. 发起 Pull Request，描述变更与验证方式

代码风格与协作约定可先阅读 [AGENTS.md](AGENTS.md)。

---

## ⚠️ 安全提示

- 默认 API Key、演示密钥仅用于本地开发，**生产务必轮换**
- 真实模型密钥请放在环境变量或 gitignore 文件中（如 `scripts/local-env.*`）
- 管理接口位于 `/api/v1/admin/**`，需 `admin` scope，勿对公网裸暴露

---

## 📄 License

本仓库暂未声明开源许可证。对外分发或二次开发前，请与维护方确认授权范围。

---

<div align="center">
Made with ❤️ · Powered by <a href="https://java.agentscope.io/">AgentScope Java</a>
</div>
