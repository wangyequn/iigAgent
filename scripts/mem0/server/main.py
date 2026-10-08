"""Minimal Mem0 OSS REST shim (self-hosted) for AMP.

Endpoints match what agentscope Mem0Client (SELF_HOSTED) expects:
  POST /memories  - add + infer
  POST /search    - semantic search
  GET  /memories  - list
  GET  /memories/{id}
  PUT  /memories/{id}
  DELETE /memories/{id}
  DELETE /memories
  GET  /docs
"""
from __future__ import annotations

import os
import uuid
from typing import Any, Optional

from fastapi import Body, FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

app = FastAPI(title="amp-mem0-shim", version="0.1.0")
# Dashboard (:3000) 跨域调用 API (:8888)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

_store: dict[str, dict[str, Any]] = {}
_memory = None
_memory_err: Optional[str] = None


def _build_memory():
    """Lazy-init mem0ai Memory with DeepSeek/OpenAI; fallback to in-process store."""
    global _memory, _memory_err
    if _memory is not None or _memory_err is not None:
        return _memory
    try:
        from mem0 import Memory

        deepseek_key = os.getenv("DEEPSEEK_API_KEY", "")
        openai_key = os.getenv("OPENAI_API_KEY", "")
        if deepseek_key:
            use_openai_embed = bool(openai_key)
            embed_dims = int(os.getenv("MEM0_EMBED_DIMS", "1536" if use_openai_embed else "384"))
            config = {
                "llm": {
                    "provider": "deepseek",
                    "config": {
                        "model": os.getenv("MEM0_LLM_MODEL", "deepseek-chat"),
                        "api_key": deepseek_key,
                        "temperature": 0.1,
                        "max_tokens": 1000,
                    },
                },
                "embedder": (
                    {
                        "provider": "openai",
                        "config": {
                            "model": os.getenv("MEM0_EMBED_MODEL", "text-embedding-3-small"),
                            "api_key": openai_key,
                            "embedding_dims": embed_dims,
                        },
                    }
                    if use_openai_embed
                    else {
                        "provider": "fastembed",
                        "config": {
                            "model": os.getenv(
                                "MEM0_EMBED_MODEL", "BAAI/bge-small-en-v1.5"
                            ),
                        },
                    }
                ),
                "vector_store": {
                    "provider": "qdrant",
                    "config": {
                        "collection_name": os.getenv(
                            "MEM0_COLLECTION",
                            "amp_mem_openai" if use_openai_embed else "amp_mem_bge384",
                        ),
                        "path": os.getenv("MEM0_QDRANT_PATH", "/tmp/qdrant"),
                        "embedding_model_dims": embed_dims,
                    },
                },
            }
            _memory = Memory.from_config(config)
        elif openai_key:
            _memory = Memory()
        else:
            _memory_err = "no LLM key (DEEPSEEK_API_KEY/OPENAI_API_KEY); using local store"
            return None
    except Exception as e:  # noqa: BLE001
        _memory_err = f"mem0 init failed: {e}"
        return None
    return _memory


class Message(BaseModel):
    role: str = "user"
    content: str = ""


class AddBody(BaseModel):
    messages: list[Message] = Field(default_factory=list)
    user_id: Optional[str] = None
    agent_id: Optional[str] = None
    run_id: Optional[str] = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    infer: bool = True


class SearchBody(BaseModel):
    query: str = ""
    user_id: Optional[str] = None
    agent_id: Optional[str] = None
    run_id: Optional[str] = None
    limit: int = 5
    filters: dict[str, Any] = Field(default_factory=dict)


class UpdateBody(BaseModel):
    text: str


@app.get("/")
def root():
    return {"service": "amp-mem0-shim", "store": "mem0" if _build_memory() else "local",
            "note": _memory_err}


@app.get("/docs")
def docs():
    return {"ok": True}


@app.post("/memories")
def add_memories(body: AddBody):
    texts = [m.content for m in body.messages if m.content and m.content.strip()]
    if not texts:
        raise HTTPException(400, "messages required")
    mem = _build_memory()
    meta = dict(body.metadata or {})
    results = []
    if mem is not None:
        payload = "\n".join(texts)
        try:
            res = mem.add(
                payload,
                user_id=body.user_id or "anonymous",
                agent_id=body.agent_id,
                run_id=body.run_id,
                metadata=meta or None,
                infer=body.infer,
            )
            items = res.get("results", res) if isinstance(res, dict) else res
            if isinstance(items, list):
                for it in items:
                    mid = it.get("id") or it.get("memory_id") or str(uuid.uuid4())
                    results.append({"id": mid, "event": it.get("event", "ADD"),
                                    "memory": it.get("memory", "")})
            elif isinstance(items, dict):
                mid = items.get("id") or str(uuid.uuid4())
                results.append({"id": mid, "event": "ADD",
                                "memory": items.get("memory", payload[:120])})
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"mem0 add failed: {e}") from e
    else:
        mid = str(uuid.uuid4())
        mem_text = texts[0][:200]
        _store[mid] = {
            "id": mid,
            "memory": mem_text,
            "user_id": body.user_id,
            "agent_id": body.agent_id,
            "run_id": body.run_id,
            "metadata": meta,
        }
        results.append({"id": mid, "event": "ADD", "memory": mem_text})
    return {"results": results}


@app.post("/search")
def search_memories(body: SearchBody):
    mem = _build_memory()
    if mem is not None:
        try:
            filters: dict[str, Any] = {}
            if body.user_id:
                filters["user_id"] = body.user_id
            if body.agent_id:
                filters["agent_id"] = body.agent_id
            if body.run_id:
                filters["run_id"] = body.run_id
            filters.update(body.filters or {})
            res = mem.search(body.query, limit=max(1, body.limit), filters=filters or None)
            items = res.get("results", res) if isinstance(res, dict) else res
            out = []
            if isinstance(items, list):
                for it in items:
                    out.append({
                        "id": it.get("id", ""),
                        "memory": it.get("memory", it.get("text", "")),
                        "score": it.get("score", 0.0),
                    })
            return {"results": out}
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"mem0 search failed: {e}") from e
    # local fallback: naive substring
    q = (body.query or "").lower()
    out = []
    for row in _store.values():
        if body.user_id and row.get("user_id") != body.user_id:
            continue
        if q and q not in (row.get("memory") or "").lower():
            continue
        out.append({"id": row["id"], "memory": row.get("memory", ""), "score": 0.5})
        if len(out) >= max(1, body.limit):
            break
    return {"results": out}


@app.get("/memories")
def list_memories(
    user_id: Optional[str] = Query(None),
    agent_id: Optional[str] = Query(None),
    run_id: Optional[str] = Query(None),
    limit: int = Query(20),
):
    mem = _build_memory()
    if mem is not None:
        try:
            filters = {}
            if user_id:
                filters["user_id"] = user_id
            if agent_id:
                filters["agent_id"] = agent_id
            if run_id:
                filters["run_id"] = run_id
            # mem0 要求至少一个过滤条件；Dashboard 可能只传 top_k
            if not filters:
                return {"results": []}
            res = mem.get_all(filters=filters, limit=max(1, limit))
            items = res.get("results", res) if isinstance(res, dict) else res
            out = []
            if isinstance(items, list):
                for it in items:
                    out.append({
                        "id": it.get("id", ""),
                        "memory": it.get("memory", it.get("text", "")),
                        "text": it.get("memory", it.get("text", "")),
                        "metadata": it.get("metadata", {}),
                    })
            return {"results": out}
        except Exception as e:  # noqa: BLE001
            return {"results": [], "error": str(e)}
    rows = list(_store.values())
    if user_id:
        rows = [r for r in rows if r.get("user_id") == user_id]
    return {"results": [{"id": r["id"], "text": r.get("memory", ""), "metadata": r.get("metadata", {})}
                        for r in rows[:limit]]}


@app.get("/memories/{memory_id}")
def get_memory(memory_id: str):
    mem = _build_memory()
    if mem is not None:
        try:
            it = mem.get(memory_id)
            return it if isinstance(it, dict) else {"id": memory_id, "memory": str(it)}
        except Exception as e:  # noqa: BLE001
            raise HTTPException(404, f"not found: {e}") from e
    row = _store.get(memory_id)
    if not row:
        raise HTTPException(404, "not found")
    return row


@app.put("/memories/{memory_id}")
def update_memory(memory_id: str, body: UpdateBody):
    mem = _build_memory()
    if mem is not None:
        try:
            mem.update(memory_id, body.text)
            return {"id": memory_id, "text": body.text}
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"mem0 update failed: {e}") from e
    if memory_id not in _store:
        raise HTTPException(404, "not found")
    _store[memory_id]["memory"] = body.text
    return {"id": memory_id, "text": body.text}


@app.delete("/memories/{memory_id}")
def delete_memory(memory_id: str):
    mem = _build_memory()
    if mem is not None:
        try:
            mem.delete(memory_id)
            return {"deleted": memory_id}
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"mem0 delete failed: {e}") from e
    _store.pop(memory_id, None)
    return {"deleted": memory_id}


@app.delete("/memories")
def delete_all(
    user_id: Optional[str] = Query(None),
    agent_id: Optional[str] = Query(None),
):
    mem = _build_memory()
    if mem is not None:
        try:
            if user_id:
                mem.delete_all(user_id=user_id)
            elif agent_id:
                mem.delete_all(agent_id=agent_id)
            else:
                mem.delete_all()
            return {"deleted": True}
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"mem0 delete_all failed: {e}") from e
    global _store
    if user_id:
        _store = {k: v for k, v in _store.items() if v.get("user_id") != user_id}
    else:
        _store = {}
    return {"deleted": True}

# ---------- Minimal auth for official Dashboard ----------
import hashlib
import hmac
import time

_users: dict[str, dict[str, Any]] = {}
_tokens: dict[str, dict[str, Any]] = {}
_auth_disabled = os.getenv("AUTH_DISABLED", "true").lower() in ("1", "true", "yes")


def _hash_pw(pw: str) -> str:
    return hashlib.sha256(("amp-mem0:" + pw).encode()).hexdigest()


def _issue(user_id: str) -> dict[str, str]:
    access = "atk_" + uuid.uuid4().hex
    refresh = "rtk_" + uuid.uuid4().hex
    now = time.time()
    _tokens[access] = {"user_id": user_id, "kind": "access", "exp": now + 3600}
    _tokens[refresh] = {"user_id": user_id, "kind": "refresh", "exp": now + 30 * 86400}
    return {"access_token": access, "refresh_token": refresh, "token_type": "bearer"}


def _user_from_auth() -> dict[str, Any]:
    from fastapi import Request

    return {"id": "admin", "name": "Admin", "email": "admin@amp.local"}


class RegisterBody(BaseModel):
    name: str = "Admin"
    email: str
    password: str


class LoginBody(BaseModel):
    email: str
    password: str


class RefreshBody(BaseModel):
    refresh_token: str


@app.get("/auth/setup-status")
def setup_status():
    return {"needsSetup": len(_users) == 0 and not _auth_disabled}


@app.post("/auth/register")
def register(body: RegisterBody):
    email = (body.email or "").strip().lower()
    if not email or not body.password:
        raise HTTPException(400, "email and password required")
    if email in _users and _users[email].get("role") == "admin" and _users:
        # allow first admin only when empty; otherwise 403 like official
        if any(u.get("role") == "admin" for u in _users.values()):
            raise HTTPException(403, "registration closed")
    uid = "u_" + uuid.uuid4().hex[:12]
    _users[email] = {
        "id": uid,
        "name": body.name,
        "email": email,
        "password": _hash_pw(body.password),
        "role": "admin",
    }
    return _issue(uid)


@app.post("/auth/login")
def login(body: LoginBody):
    email = (body.email or "").strip().lower()
    row = _users.get(email)
    if not row or row.get("password") != _hash_pw(body.password or ""):
        raise HTTPException(401, "invalid credentials")
    return _issue(row["id"])


@app.post("/auth/refresh")
def refresh(body: RefreshBody):
    tok = _tokens.get(body.refresh_token or "")
    if not tok or tok.get("kind") != "refresh":
        raise HTTPException(401, "invalid refresh token")
    return _issue(tok["user_id"])


@app.get("/auth/me")
def me():
    return {
        "id": "u_admin",
        "name": "Admin",
        "email": os.getenv("MEM0_ADMIN_EMAIL", "admin@amp.local").lower(),
        "role": "admin",
        "created_at": "2026-01-01T00:00:00Z",
    }


@app.post("/auth/change-password")
def change_password(body: dict = Body(default={})):
    return {"ok": True}


@app.post("/auth/onboarding-complete")
def onboarding_complete():
    return {"ok": True}


@app.get("/api-keys")
def list_api_keys():
    return []


@app.post("/api-keys")
def create_api_key(body: dict = Body(default={})):
    now = "2026-01-01T00:00:00Z"
    key = "m0sk_" + uuid.uuid4().hex
    return {
        "id": uuid.uuid4().hex[:12],
        "label": (body or {}).get("label") or "default",
        "key": key,
        "key_prefix": key[:10],
        "created_at": now,
        "last_used_at": None,
    }


@app.delete("/api-keys/{key_id}")
def revoke_api_key(key_id: str):
    return {"deleted": key_id}


@app.get("/requests")
def list_requests(limit: int = Query(50)):
    return []


@app.get("/entities")
def list_entities():
    return []


@app.delete("/entities/{entity_type}/{entity_id}")
def delete_entity(entity_type: str, entity_id: str):
    return {"deleted": True}


@app.get("/configure")
def get_configure():
    return {
        "llm": {"provider": "deepseek", "config": {"model": "deepseek-chat"}},
        "embedder": {"provider": "fastembed", "config": {"model": "BAAI/bge-small-en-v1.5"}},
        "vector_store": {"provider": "qdrant", "config": {"collection_name": "amp_mem_bge384"}},
    }


@app.get("/configure/providers")
def configure_providers():
    return {
        "llm_providers": ["openai", "deepseek", "anthropic", "gemini"],
        "embedder_providers": ["openai", "fastembed", "huggingface"],
    }


@app.post("/configure")
def set_configure(body: dict = Body(default={})):
    return {"ok": True, "config": body}


@app.post("/reset")
def reset_all():
    global _store
    _store = {}
    mem = _build_memory()
    if mem is not None:
        try:
            mem.delete_all()
        except Exception:
            pass
    return {"reset": True}


# Seed a usable admin so Dashboard login works out of the box.
_seed_email = os.getenv("MEM0_ADMIN_EMAIL", "admin@amp.local").lower()
_seed_pw = os.getenv("MEM0_ADMIN_PASSWORD", "amp-mem0-admin")
if _seed_email not in _users:
    _users[_seed_email] = {
        "id": "u_admin",
        "name": "Admin",
        "email": _seed_email,
        "password": _hash_pw(_seed_pw),
        "role": "admin",
    }
