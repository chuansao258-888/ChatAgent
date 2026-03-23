import os
import threading
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

try:
    from FlagEmbedding import FlagReranker
except ImportError as exc:  # pragma: no cover
    raise RuntimeError(
        "FlagEmbedding is required. Install dependencies from requirements.txt first."
    ) from exc


DEFAULT_MODEL = os.getenv("BGE_RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
DEFAULT_QUERY_MAX_LENGTH = int(os.getenv("BGE_RERANKER_QUERY_MAX_LENGTH", "256"))
DEFAULT_PASSAGE_MAX_LENGTH = int(os.getenv("BGE_RERANKER_PASSAGE_MAX_LENGTH", "512"))
DEFAULT_USE_FP16 = os.getenv("BGE_RERANKER_USE_FP16", "true").lower() == "true"
DEFAULT_DEVICE = os.getenv("BGE_RERANKER_DEVICE", "")
DEFAULT_HOST = os.getenv("BGE_RERANKER_HOST", "0.0.0.0")
DEFAULT_PORT = int(os.getenv("BGE_RERANKER_PORT", "7997"))


class RerankRequest(BaseModel):
    model: Optional[str] = Field(default=None, description="FlagEmbedding model id or local path")
    query: str
    documents: List[str]
    top_n: int = 8
    return_documents: bool = False


class RerankResult(BaseModel):
    index: int
    relevance_score: float
    document: Optional[str] = None


class RerankResponse(BaseModel):
    model: str
    results: List[RerankResult]


class RerankerRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._rerankers: dict[str, FlagReranker] = {}

    def get(self, model_name: str) -> FlagReranker:
        with self._lock:
            model_name = normalize_model_name(model_name)
            reranker = self._rerankers.get(model_name)
            if reranker is not None:
                return reranker

            kwargs = {
                "query_max_length": DEFAULT_QUERY_MAX_LENGTH,
                "passage_max_length": DEFAULT_PASSAGE_MAX_LENGTH,
                "use_fp16": DEFAULT_USE_FP16,
            }
            if DEFAULT_DEVICE:
                kwargs["devices"] = [DEFAULT_DEVICE]

            reranker = FlagReranker(model_name, **kwargs)
            self._rerankers[model_name] = reranker
            return reranker


def normalize_model_name(model_name: str) -> str:
    normalized = (model_name or DEFAULT_MODEL).strip()
    if "/" in normalized:
        return normalized
    if normalized.startswith("bge-"):
        return f"BAAI/{normalized}"
    return normalized


registry = RerankerRegistry()
app = FastAPI(title="BGE Reranker Server", version="1.0.0")


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "default_model": DEFAULT_MODEL,
        "query_max_length": DEFAULT_QUERY_MAX_LENGTH,
        "passage_max_length": DEFAULT_PASSAGE_MAX_LENGTH,
        "use_fp16": DEFAULT_USE_FP16,
        "device": DEFAULT_DEVICE or "auto",
    }


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest) -> RerankResponse:
    if not request.query.strip():
        raise HTTPException(status_code=400, detail="query must not be blank")
    if not request.documents:
        raise HTTPException(status_code=400, detail="documents must not be empty")

    model_name = request.model or DEFAULT_MODEL
    model_name = normalize_model_name(model_name)
    reranker = registry.get(model_name)
    pairs = [[request.query, document] for document in request.documents]

    scores = reranker.compute_score(pairs, normalize=True)
    if isinstance(scores, float):
        scores = [scores]
    if len(scores) != len(request.documents):
        raise HTTPException(status_code=500, detail="reranker returned unexpected score count")

    ranked = sorted(
        enumerate(scores),
        key=lambda item: item[1],
        reverse=True
    )[: max(1, min(request.top_n, len(request.documents)))]

    results = []
    for index, score in ranked:
        results.append(
            RerankResult(
                index=index,
                relevance_score=float(score),
                document=request.documents[index] if request.return_documents else None,
            )
        )

    return RerankResponse(model=model_name, results=results)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=DEFAULT_HOST, port=DEFAULT_PORT)
