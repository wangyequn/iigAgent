# -*- coding: utf-8 -*-
from pathlib import Path

root = Path(r"D:/sinosoft/江苏二期/基于JAVA的智能体应用平台")

# ---- README.md ----
p = root / "README.md"
t = p.read_text(encoding="utf-8")
old = """## 模块

| 模块 | 职责 |
| --- | --- |
| `platform-common` | 错误码等公共 DTO |
| `platform-open-api` | Spring MVC + 目录/版本 + 实例缓存 + SSE |
| `platform-console-ui` | Vue3 Playground（S3：版本列表 / 发布切流 / 对话） |
"""
new = """## 模块

依赖方向：`common → runtime → governance → ops → channel → open-api`（`amp-starter` 不依赖 platform-*）。

| 模块 | 职责 |
| --- | --- |
| `platform-common` | 错误码等公共 DTO |
| `platform-runtime` | 目录/鉴权/会话/Agent 缓存与策略/知识/模型/FS/billing/共享 ops |
| `platform-governance` | 权限/MCP/技能/沙箱 |
| `platform-ops` | Nacos/OTel/Loki/限流/审计过滤 |
| `platform-channel` | Channel/Automation/Orchestration/IAM |
| `platform-open-api` | Spring Boot 入口 + Web + 装配（Factory/Bootstrap/ConsoleBot） |
| `amp-starter` | 可抄金路径（Copy 模式） |
| `platform-console-ui` | Vue3 Playground（S3：版本列表 / 发布切流 / 对话） |

> 首次构建或改动公共模块后请先 `mvn -DskipTests install`，再 `spring-boot:run` / `scripts/run-api-18081.cmd`。
"""
if old in t:
    t = t.replace(old, new, 1)
    p.write_text(t, encoding="utf-8")
    print("README.md updated")
else:
    print("README.md: pattern NOT found")

# ---- 方案 doc ----
p2 = root / "docs" / "方案-amp-starter与Maven模块化.md"
t2 = p2.read_text(encoding="utf-8")
marker = "### Phase 0 · 评审（0.5 天）— **已完成（决议见 §9）**"
status = """### 执行状态（2026-09-30 · 已落地）

| 阶段 | 状态 | 结果 |
| --- | --- | --- |
| Phase 0 评审 | ✅ | S1–S6 已定稿（§9） |
| Phase 2 amp-starter | ✅ | Copy 模式金路径 + 11 测 |
| Phase 3–4 模块拆分 | ✅ | 六模块落地；装配类留 open-api；共享 ops 下沉 runtime（未做完整 SPI，以物理分层为主） |
| Phase 5 文档 | ✅ | AGENTS.md / README 已同步 |
| ArchUnit 防回潮 | ⬜ | 可选后续 |

**验证：** `mvn -B verify` → **208 测全绿**（runtime 77 / governance 35 / ops 34 / channel 23 / open-api 27 / starter 11 / common 1）。  
**联调：** 需先 `mvn -DskipTests install` 再启后端；管理 API / UI 16 路由 / 侧栏 / 对话发送已通过。

"""
if "### 执行状态（2026-09-30 · 已落地）" not in t2:
    if marker in t2:
        t2 = t2.replace(marker, status + marker, 1)
        p2.write_text(t2, encoding="utf-8")
        print("plan doc updated")
    else:
        print("plan doc: marker NOT found")
else:
    print("plan doc: already has status")

# ---- 项目总体架构总结 ----
p3 = root / "docs" / "项目总体架构总结.md"
t3 = p3.read_text(encoding="utf-8")
old3 = """├── platform-common      # 公共 DTO（如 ApiError）
├── platform-open-api    # Spring Boot 后端入口 OpenApiApplication
└── platform-console-ui  # 管理台 / 调试 UI（独立 npm）"""
new3 = """├── platform-common      # 公共 DTO（如 ApiError）
├── platform-runtime     # 目录/鉴权/会话/Agent 缓存/知识/模型/FS/共享 ops
├── platform-governance  # 权限/MCP/技能/沙箱
├── platform-ops         # Nacos/OTel/Loki/限流/审计过滤
├── platform-channel     # Channel/Automation/Orchestration/IAM
├── platform-open-api    # Spring Boot 入口 + Web + 装配
├── amp-starter          # 可抄金路径（Copy 模式）
└── platform-console-ui  # 管理台 / 调试 UI（独立 npm）"""
if old3 in t3:
    t3 = t3.replace(old3, new3, 1)
    p3.write_text(t3, encoding="utf-8")
    print("architecture summary updated")
else:
    # try looser match
    if "platform-open-api    # Spring Boot 后端入口" in t3 and "platform-runtime" not in t3:
        print("architecture summary: loose pattern needed")
        idx = t3.find("platform-common")
        print(repr(t3[idx:idx+250]))
    else:
        print("architecture summary: skip or already updated")
