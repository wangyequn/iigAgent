# qwen3-embedding-4b 本地向量化服务（OpenAI 兼容 /v1/embeddings）
#
# 平台对接（application.yml）：
#   amp.rag.embedder: http
#   amp.rag.http.base-url: http://127.0.0.1:18090/v1
#   amp.rag.http.model: qwen3-embedding-4b
#
# 依赖安装（CPU）：
#   python -m venv venv
#   venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
#       torch --index-url https://download.pytorch.org/whl/cpu
#   venv\Scripts\python.exe -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
#       transformers modelscope fastapi uvicorn
#
# 模型权重（ModelScope 国内源）：
#   venv\Scripts\python.exe -c "from modelscope import snapshot_download; snapshot_download('Qwen/Qwen3-Embedding-4B')"
#
# 启动：venv\Scripts\python.exe embed-server.py
import os

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

MODEL_DIR = os.environ.get(
    "QWEN3_EMBED_MODEL_DIR",
    os.path.expanduser(r"~/.cache/modelscope/hub/models/Qwen/Qwen3-Embedding-4B"),
)
PORT = int(os.environ.get("EMBED_PORT", "18090"))

app = FastAPI(title="qwen3-embedding local service")
_model = None
_tokenizer = None


class EmbedRequest(BaseModel):
    input: str | list[str]
    model: str | None = None


def load_model():
    global _model, _tokenizer
    if _model is not None:
        return
    from transformers import AutoModel, AutoTokenizer

    _tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, trust_remote_code=True)
    # CPU 推理：float32 保稳（无 GPU）；内存不足可换 torch_dtype=torch.float16 + device_map
    _model = AutoModel.from_pretrained(MODEL_DIR, trust_remote_code=True)
    _model.eval()


@app.post("/v1/embeddings")
def embeddings(req: EmbedRequest):
    load_model()
    import torch

    texts = [req.input] if isinstance(req.input, str) else list(req.input)
    if not texts:
        raise HTTPException(status_code=400, detail="input is required")
    # Qwen3-Embedding 推荐加检索指令前缀（查询侧）；入库侧用原文。此处统一用原文，
    # 保证存查一致；如需区分 query/doc 前缀，在平台侧传 instruction。
    inputs = _tokenizer(texts, padding=True, truncation=True, max_length=512, return_tensors="pt")
    with torch.no_grad():
        out = _model(**inputs)
        # last_hidden_state + mean pooling（Qwen3-Embedding 官方解码方式）
        hidden = out.last_hidden_state
        mask = inputs["attention_mask"].unsqueeze(-1).float()
        pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
        pooled = torch.nn.functional.normalize(pooled, dim=1)
    data = [
        {"object": "embedding", "index": i, "embedding": pooled[i].tolist()}
        for i in range(len(texts))
    ]
    return {"object": "list", "model": req.model or "qwen3-embedding-4b", "data": data,
            "usage": {"prompt_tokens": 0, "total_tokens": 0}}


@app.get("/health")
def health():
    return {"status": "UP", "model": "qwen3-embedding-4b", "loaded": _model is not None}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=PORT)
