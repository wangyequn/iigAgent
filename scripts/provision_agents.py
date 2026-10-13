#!/usr/bin/env python3
"""通过管理 API 新增智能体（不依赖 CatalogBootstrap 种子）。

用法:
  python scripts/provision_agents.py
  python scripts/provision_agents.py --base http://127.0.0.1:18081 --key s0-admin-key

幂等：已存在的 Agent / 版本 / 工具 / 知识标题会跳过；发布指针缺失时会补发布。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = os.environ.get("AMP_BASE_URL", "http://127.0.0.1:18081")
DEFAULT_KEY = os.environ.get("AMP_ADMIN_API_KEY", "s0-admin-key")
DEFAULT_MODEL = os.environ.get("AMP_MODEL_ID", "deepseek:deepseek-flash")

DEMO_PROMPT = """你是一个乐于助人的 AI 助手。用中文简洁、专业地回答；不确定时说明并追问，不要编造。"""

CONSOLE_BOT_PROMPT = """你是 AI 中台控制台的智能机器人「小助」，内嵌在控制台右下角。你本身是一个标准智能体（agentId=console-bot），
具备本系统全部能力，并与控制台 UI 深度联动。

## 身份与语气
- 中文优先，简洁、专业、可执行；不要空话。
- 用户问平台问题时，优先调用工具拿实时数据，不要编造。
- 回答后按需给出下一步建议（可点击操作）。

## 能力地图（工具）
平台状态：platform_status
Agent：list_agents, agent_detail
治理：list_permissions, list_business_tools
技能/知识：list_skills, list_knowledge, console_knowledge_search（知识问题必须检索并引用）
编排：list_workflows, list_teams
运营：usage_summary, list_audit
UI 交互：ui_navigate, ui_focus, ui_notify, ui_ask_user, ui_suggest
通用：safe_lookup（只读）、dangerous_write（危险写，需 HITL）、knowledge_search

## UI 深度交互协议（关键）
当需要驱动控制台界面时，调用 UI 工具，工具会返回 ui.actions，前端自动执行：
- 要跳页面 → ui_navigate(path)。常用路径：
  /workbench/chat 智能对话 | /workbench/approvals 审批 | /workbench/knowledge 知识库
  /agents Agent列表 | /skills 技能 | /workflows 编排 | /teams 团队
  /governance/permissions 权限 | /governance/tools 工具 | /governance/channels 渠道
  /governance/external External | /governance/iam IAM
  /ops/overview 概览 | /ops/sessions 会话 | /ops/billing 用量 | /ops/audit 审计
  /ops/sandbox 沙箱 | /ops/credentials 凭证
  /system/health 健康 | /system/config 配置 | /system/openai 调试
- 要打开实体详情 → ui_focus(entityType, entityId)
- 要提醒用户 → ui_notify(level, message)
- 要追问 → ui_ask_user(prompt, options)
- 要给快捷操作 → ui_suggest(actions JSON)

## 典型任务
1. 「帮我看看系统状态」→ platform_status + 可选 usage_summary，摘要并 ui_suggest 后续
2. 「有哪些 Agent」→ list_agents，摘要后 ui_suggest 查看详情
3. 「打开权限页」→ ui_navigate("/governance/permissions")
4. 「平台怎么用 / 最佳实践」→ console_knowledge_search，并引用 [docId#chunkId]
5. 「最近用量」→ usage_summary，必要时 ui_navigate("/ops/billing")
6. 「打开知识库」→ ui_navigate("/workbench/knowledge")

## 边界
- 不执行任意 shell、不改生产配置（配置变更请引导用户到对应管理页手动确认）
- 不确定时说明并询问；给出路径而不是编造数字
"""

BIZ_ASSIST_PROMPT = """你是登录平台（pc_platform）的业务助手「业务小助」，内嵌在业务页面供登录用户使用。

## 身份与边界
- 当前用户以登录平台 X-Access-Token 为准；工具调用会透传该票，权限是用户权限的子集。
- 只做查询与解释；写操作、改密、删用户、发公告等一律不做或引导到页面手工操作。
- 数据不确定时说明并追问，不要编造。

## 工具
- query_my_info：查我的账号信息
- query_user_by_name：按姓名/账号查用户
- query_dept_tree / query_dept_users：查组织
- query_roles / query_my_menus：查角色与菜单权限
- query_dict：查字典
- query_announcements：查我的公告

## 回答要求
- 中文、简洁、可执行
- 涉及列表时给出关键字段，不要倾倒全量 JSON
- 需要跳转页面时提示用户到对应菜单操作
"""

CONSOLE_BOT_TOOLS = [
    ("platform_status", "查询 AI 中台运行状态（就绪/限流/运行时配置）。只读。"),
    ("list_agents", "列出当前租户 Agent 与发布版本。只读。"),
    ("agent_detail", "查看单个 Agent 详情与版本列表。只读。"),
    ("list_permissions", "列出 Agent 权限规则与模式。只读。"),
    ("list_business_tools", "列出业务工具与 MCP 服务器。只读。"),
    ("list_skills", "列出技能市场技能。只读。"),
    ("list_knowledge", "列出知识库文档。只读。"),
    ("console_knowledge_search", "知识库检索（带引用片段）。只读。"),
    ("list_workflows", "列出 Workflow 与最近运行。只读。"),
    ("list_teams", "列出 Team 协作团队。只读。"),
    ("usage_summary", "查询配额与用量汇总。只读。"),
    ("list_audit", "查看最近审计日志。只读。"),
    ("ui_navigate", "指挥控制台 UI 跳转页面（返回 ui.actions）。只读。"),
    ("ui_focus", "在 UI 中聚焦/打开实体详情。只读。"),
    ("ui_notify", "弹出 Toast 通知。只读。"),
    ("ui_ask_user", "在机器人面板追问（可带快捷选项）。只读。"),
    ("ui_suggest", "生成可点击快捷操作芯片。只读。"),
]

BIZ_ASSIST_HTTP_TOOLS = [
    (
        "query_my_info",
        "查询当前登录用户信息（透传用户票）",
        "/sys/user/getUserInfo",
        {},
        "system:user:query",
    ),
    (
        "query_user_by_name",
        "按姓名/账号查询用户列表（受数据权限约束）",
        "/sys/user/list",
        {
            "realname": {"type": "string", "description": "姓名"},
            "username": {"type": "string", "description": "账号"},
            "pageNo": {"type": "integer", "description": "页码，默认1"},
            "pageSize": {"type": "integer", "description": "每页条数，默认10"},
        },
        "system:user:query",
    ),
    (
        "query_dept_tree",
        "查询我的部门树",
        "/sys/sysDepart/queryMyDeptTreeList",
        {},
        "system:depart:query",
    ),
    (
        "query_dept_users",
        "按部门 ID 查询用户",
        "/sys/sysDepart/getUsersByDepartId",
        {"id": {"type": "string", "description": "部门 ID"}},
        "system:depart:query",
    ),
    (
        "query_roles",
        "查询当前用户角色",
        "/sys/role/getRolesByToken",
        {},
        "system:role:query",
    ),
    (
        "query_my_menus",
        "查询当前用户菜单/权限（按登录票）",
        "/sys/permission/getUserPermissionByToken",
        {},
        "",
    ),
    (
        "query_dict",
        "查询字典项（dictCode 路径参数）",
        "/sys/dict/getDictItems/{dictCode}",
        {"dictCode": {"type": "string", "description": "字典编码"}},
        "",
    ),
    (
        "query_announcements",
        "查询我的公告列表",
        "/sys/annountCement/listByUser",
        {},
        "",
    ),
]

KNOWLEDGE_DOCS = [
    (
        "控制台智能机器人使用指南",
        """# 控制台智能机器人（小助）使用指南

## 是什么
右下角浮动机器人，agentId=console-bot，是 AI 中台的**平台最佳实践智能体**：
目录可管理、工具可治理、知识可检索、权限可审计。

## 能做什么
1. 查询平台：Agent / 权限 / 工具 / 技能 / 知识 / 编排 / 用量 / 审计
2. 驱动 UI：跳转页面、打开详情、Toast、追问、快捷芯片
3. 通用工具：safe_lookup（只读）、dangerous_write（危险写，需 HITL）

## 如何管理
- Agent：控制台「Agent 列表」→ console-bot（可改系统提示词、版本、发布）
- 工具：「MCP 与工具」→ 业务工具，owner=console-bot 的内置工具
- 权限：「权限与模式」→ 选 agentId=console-bot
- 知识：「知识库」检索「小助 / console-bot / 最佳实践」

## UI 交互约定
工具返回 JSON 中的 ui.actions 会被前端自动执行：
navigate / focus / toast / askUser / chips。
页面可用 botAsk() 或标题「问小助」注入上下文。

## 最佳实践
1. 平台问题优先调工具，不编造数字
2. 危险写操作走 HITL，不跳过确认
3. 回答后给出可点击下一步
4. 知识问题必须引用 [docId#chunkId]
""",
    ),
    (
        "AI中台能力地图",
        """# AI 中台能力地图

## 工作台
- 智能对话：业务 Agent 多轮对话 / SSE / HITL 审批
- 审批中心：工具调用人工确认
- 知识库：文档上传、检索、引用

## Agent 中心
- Agent 列表 / 版本 / 发布切流
- 技能市场：上传、审核、可见性
- 编排 Workflow：ECHO / AGENT / HUMAN_GATE
- Team 协作：多 Agent 组队

## 治理中心
- 权限与模式：ALLOW / ASK / DENY
- MCP 与工具：业务工具登记（IN_PROCESS / HTTP）、MCP 服务器
- 渠道 Channel / External Agent / IAM-RBAC

## 运营中心
- 运行概览、会话日志、配额用量、审计、沙箱 FS、模型凭证

## 系统
- 环境就绪（Redis/PG）、配置热更、OpenAI 兼容调试

## 智能机器人小助
内嵌 console-bot，覆盖上述能力的查询与 UI 导航，是平台最佳实践入口。
""",
    ),
]

SKILL_NAME = "console-bot-best-practice"
SKILL_CONTENT = """# console-bot-best-practice

## 何时使用
需要了解如何把「对话式运维 / 控制台副驾」做成可治理的平台智能体时。

## 标准形态
1. **目录化**：作为 Agent 播种（agentId=console-bot），可版本、可发布
2. **工具治理**：工具登记 business_tool（owner=console-bot），权限 ALLOW/ASK
3. **知识增强**：使用指南 + 能力地图入库，回答带引用
4. **UI 联动**：ui_* 工具返回 ui.actions，前端执行导航/Toast/聚焦
5. **HITL**：危险写操作保持 ASK

## 实现要点
- 工具结果统一 {ok, data, ui:{actions:[]}}
- 页面经 botBus 注入上下文
- 系统提示词写清能力地图与 UI 协议
"""


class Client:
    def __init__(self, base: str, key: str) -> None:
        self.base = base.rstrip("/")
        self.key = key

    def request(self, method: str, path: str, body: dict | None = None) -> tuple[int, dict | list | str]:
        url = self.base + path
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.key)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read().decode("utf-8")
                if not raw:
                    return resp.status, {}
                try:
                    return resp.status, json.loads(raw)
                except json.JSONDecodeError:
                    return resp.status, raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                return e.code, json.loads(raw)
            except json.JSONDecodeError:
                return e.code, raw

    def ok(self, method: str, path: str, body: dict | None = None, allow: tuple[int, ...] = (200, 201, 409)) -> dict:
        status, payload = self.request(method, path, body)
        if status not in allow and status >= 400:
            raise RuntimeError(f"{method} {path} -> {status}: {payload}")
        return payload if isinstance(payload, dict) else {"status": status, "payload": payload}


def ensure_agent(c: Client, agent_id: str, name: str, model_id: str, sys_prompt: str) -> None:
    status, existing = c.request("GET", "/api/v1/agents")
    agents = existing.get("agents", []) if isinstance(existing, dict) else []
    known = {a.get("agentId") or a.get("id") for a in agents}
    if agent_id not in known:
        c.ok("POST", "/api/v1/admin/agents", {"agentId": agent_id, "name": name}, allow=(200, 201))
        print(f"[agent] created {agent_id}")
    else:
        print(f"[agent] exists {agent_id}")

    status, versions_body = c.request("GET", f"/api/v1/agents/{agent_id}/versions")
    versions = versions_body.get("versions", []) if isinstance(versions_body, dict) else []
    release = versions_body.get("releaseVersion") if isinstance(versions_body, dict) else None
    has_v1 = any(v.get("version") == 1 for v in versions)
    if not has_v1:
        c.ok(
            "POST",
            f"/api/v1/admin/agents/{agent_id}/versions",
            {"version": 1, "modelId": model_id, "sysPrompt": sys_prompt},
            allow=(200, 201),
        )
        print(f"[version] created {agent_id} v1")
    else:
        print(f"[version] exists {agent_id} v1")

    if release != 1:
        c.ok("POST", f"/api/v1/admin/agents/{agent_id}/versions/1/publish", {}, allow=(200, 201, 409))
        print(f"[release] published {agent_id} -> v1")
    else:
        print(f"[release] already {agent_id} -> v1")


def ensure_mode(c: Client, agent_id: str, mode: str = "DEFAULT") -> None:
    c.ok("POST", "/api/v1/admin/governance/permission-mode", {"agentId": agent_id, "mode": mode})
    print(f"[mode] {agent_id}={mode}")


def ensure_tool(c: Client, tool_name: str, description: str, kind: str, config: str | None) -> None:
    status, body = c.request("GET", "/api/v1/admin/business-tools")
    tools = body.get("tools", []) if isinstance(body, dict) else []
    if any(t.get("toolName") == tool_name for t in tools):
        print(f"[tool] exists {tool_name}")
        return
    c.ok(
        "POST",
        "/api/v1/admin/business-tools",
        {
            "toolName": tool_name,
            "description": description,
            "kind": kind,
            "config": config,
        },
    )
    print(f"[tool] registered {tool_name}")


def ensure_rule(c: Client, agent_id: str, tool_name: str, behavior: str) -> None:
    c.ok(
        "POST",
        "/api/v1/admin/governance/permissions",
        {
            "agentId": agent_id,
            "toolName": tool_name,
            "behavior": behavior,
            "ruleContent": "*",
            "source": "platform",
        },
    )
    print(f"[rule] {agent_id} {tool_name}={behavior}")


def ensure_knowledge(c: Client, title: str, content: str) -> None:
    status, body = c.request("GET", "/api/v1/admin/knowledge/documents")
    docs = body.get("documents", body.get("docs", [])) if isinstance(body, dict) else []
    # Provider 可能给标题追加 " (source)" 后缀，按包含匹配即可
    if isinstance(docs, list) and any(title in (d.get("title") or "") for d in docs if isinstance(d, dict)):
        print(f"[knowledge] exists {title}")
        return
    c.ok(
        "POST",
        "/api/v1/admin/knowledge/documents",
        {"title": title, "source": "provision", "content": content},
    )
    print(f"[knowledge] ingested {title}")


def ensure_skill(c: Client) -> None:
    status, body = c.request("GET", "/api/v1/admin/skills")
    skills = body.get("skills", []) if isinstance(body, dict) else []
    if any(s.get("name") == SKILL_NAME for s in skills):
        print(f"[skill] exists {SKILL_NAME}")
        return
    c.ok(
        "POST",
        "/api/v1/admin/skills",
        {
            "name": SKILL_NAME,
            "version": "1.0.0",
            "description": "控制台智能机器人最佳实践：如何用小助管理平台并驱动 UI（平台参考实现）",
            "skillContent": SKILL_CONTENT,
        },
    )
    print(f"[skill] uploaded {SKILL_NAME}")


def http_tool_config(base_url: str, path: str, properties: dict, perm_code: str) -> str:
    url = base_url.rstrip("/") + path
    cfg = {
        "url": url,
        "method": "GET",
        "readOnly": True,
        "risk": "low",
        "authMode": "user_token",
        "ownerSystem": "pc_platform",
        "parameters": {"type": "object", "properties": properties or {}},
    }
    if perm_code:
        cfg["permCode"] = perm_code
    return json.dumps(cfg, ensure_ascii=False)


def provision(c: Client, model_id: str, pc_base: str | None) -> None:
    ensure_agent(c, "demo", "demo", model_id, DEMO_PROMPT)
    ensure_mode(c, "demo", "DEFAULT")
    ensure_tool(c, "safe_lookup", "通用只读查询示例", "IN_PROCESS", None)
    ensure_tool(c, "dangerous_write", "危险写操作示例（需 HITL 确认）", "IN_PROCESS", None)
    ensure_rule(c, "demo", "safe_lookup", "ALLOW")
    ensure_rule(c, "demo", "knowledge_search", "ALLOW")
    ensure_rule(c, "demo", "dangerous_write", "ASK")

    ensure_agent(c, "console-bot", "控制台智能机器人", model_id, CONSOLE_BOT_PROMPT)
    ensure_mode(c, "console-bot", "DEFAULT")
    owner_cfg = json.dumps(
        {"owner": "console-bot", "builtin": True, "surface": "console-bot"}, ensure_ascii=False
    )
    for name, desc in CONSOLE_BOT_TOOLS:
        ensure_tool(c, name, desc, "IN_PROCESS", owner_cfg)
    for name, _ in CONSOLE_BOT_TOOLS:
        ensure_rule(c, "console-bot", name, "ALLOW")
    ensure_rule(c, "console-bot", "safe_lookup", "ALLOW")
    ensure_rule(c, "console-bot", "knowledge_search", "ALLOW")
    ensure_rule(c, "console-bot", "dangerous_write", "ASK")
    for title, content in KNOWLEDGE_DOCS:
        ensure_knowledge(c, title, content)
    ensure_skill(c)

    ensure_agent(c, "biz-assist", "登录平台业务助手", model_id, BIZ_ASSIST_PROMPT)
    ensure_mode(c, "biz-assist", "DEFAULT")
    for tool in (
        "query_my_info",
        "query_user_by_name",
        "query_dept_tree",
        "query_dept_users",
        "query_roles",
        "query_my_menus",
        "query_dict",
        "query_announcements",
        "knowledge_search",
        "safe_lookup",
    ):
        ensure_rule(c, "biz-assist", tool, "ALLOW")
    ensure_rule(c, "biz-assist", "dangerous_write", "ASK")
    if pc_base:
        for name, desc, path, props, perm in BIZ_ASSIST_HTTP_TOOLS:
            ensure_tool(c, name, desc, "HTTP", http_tool_config(pc_base, path, props, perm))
    else:
        print("[tool] biz-assist HTTP tools skipped (no --pc-base / AMP_PC_PLATFORM_BASE_URL)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Provision agents via admin API")
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument("--key", default=DEFAULT_KEY)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument(
        "--pc-base",
        default=os.environ.get("AMP_PC_PLATFORM_BASE_URL", ""),
        help="登录平台基址；非空则登记 biz-assist HTTP 工具",
    )
    args = parser.parse_args()
    c = Client(args.base, args.key)
    try:
        status, health = c.request("GET", "/api/v1/health/ready")
    except urllib.error.URLError as e:
        print(f"backend unreachable: {e}", file=sys.stderr)
        return 1
    if status != 200:
        print(f"backend not ready: {status} {health}", file=sys.stderr)
        return 1
    provision(c, args.model, args.pc_base or None)
    print("provision done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
