import gc
import json
import logging
import os
import threading
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field


def env_flag(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


if env_flag("BGE_RERANKER_OFFLINE", False):
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    os.environ.setdefault("HF_HUB_OFFLINE", "1")

try:
    from FlagEmbedding import FlagReranker
except ImportError as exc:  # pragma: no cover
    raise RuntimeError(
        "FlagEmbedding is required. Install dependencies from requirements.txt first."
    ) from exc

try:  # pragma: no cover
    import psutil
except ImportError:  # pragma: no cover
    psutil = None

try:  # pragma: no cover
    import torch
except ImportError:  # pragma: no cover
    torch = None


logging.basicConfig(
    level=os.getenv("BGE_RERANKER_LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
)
logger = logging.getLogger("bge-reranker-server")


DEFAULT_MODEL = os.getenv("BGE_RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
DEFAULT_QUERY_MAX_LENGTH = int(os.getenv("BGE_RERANKER_QUERY_MAX_LENGTH", "256"))
DEFAULT_PASSAGE_MAX_LENGTH = int(os.getenv("BGE_RERANKER_PASSAGE_MAX_LENGTH", "512"))
DEFAULT_USE_FP16 = env_flag("BGE_RERANKER_USE_FP16", True)
DEFAULT_DEVICE = os.getenv("BGE_RERANKER_DEVICE", "").strip()
DEFAULT_HOST = os.getenv("BGE_RERANKER_HOST", "0.0.0.0")
DEFAULT_PORT = int(os.getenv("BGE_RERANKER_PORT", "7997"))
PRELOAD_ON_START = env_flag("BGE_RERANKER_PRELOAD_ON_START", True)
PRELOAD_MODELS_ENV = os.getenv("BGE_RERANKER_PRELOAD_MODELS", DEFAULT_MODEL)
WARMUP_ENABLED = env_flag("BGE_RERANKER_WARMUP_ENABLED", True)
WARMUP_QUERY = os.getenv("BGE_RERANKER_WARMUP_QUERY", "warmup")
WARMUP_DOCS_ENV = os.getenv("BGE_RERANKER_WARMUP_DOCS", "")
MAX_CONCURRENT_REQUESTS = int(os.getenv("BGE_RERANKER_MAX_CONCURRENT_REQUESTS", "2"))
CPU_THREADS = int(os.getenv("BGE_RERANKER_CPU_THREADS", "0"))
DEADLINE_HEADER = os.getenv("BGE_RERANKER_DEADLINE_HEADER", "X-Reranker-Deadline-Epoch-Ms")


class RerankRequest(BaseModel):
    model: Optional[str] = Field(default=None, description="FlagEmbedding model id or local path")
    query: str
    documents: list[str]
    top_n: int = 8
    return_documents: bool = False


class RerankResult(BaseModel):
    index: int
    relevance_score: float
    document: Optional[str] = None


class RerankResponse(BaseModel):
    model: str
    results: list[RerankResult]


class ServiceStatus:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data: dict[str, Any] = {
            "status": "initializing",
            "state": "INITIALIZING",
            "default_model": normalize_model_name(DEFAULT_MODEL),
            "loaded_models": [],
            "device": DEFAULT_DEVICE or "auto",
            "use_fp16": DEFAULT_USE_FP16,
            "query_max_length": DEFAULT_QUERY_MAX_LENGTH,
            "passage_max_length": DEFAULT_PASSAGE_MAX_LENGTH,
            "preload_on_start": PRELOAD_ON_START,
            "warmup_enabled": WARMUP_ENABLED,
            "max_concurrent_requests": MAX_CONCURRENT_REQUESTS,
            "startup_duration_ms": None,
            "preload_duration_ms": None,
            "warmup_duration_ms": None,
            "warmup_complete": False,
            "last_warmup_at": None,
            "last_error": None,
            "offline_mode": env_flag("BGE_RERANKER_OFFLINE", False),
            "memory": memory_snapshot(),
        }

    def set_state(self, state: str, status: str, **extra: Any) -> None:
        with self._lock:
            self._data["state"] = state
            self._data["status"] = status
            self._data.update(extra)
            self._data["memory"] = memory_snapshot()

    def set_loaded_models(self, loaded_models: list[str]) -> None:
        with self._lock:
            self._data["loaded_models"] = sorted(set(loaded_models))

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._data)

    def is_ready(self) -> bool:
        with self._lock:
            return self._data["state"] == "READY"


class RequestStats:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data: dict[str, Any] = {
            "requests_total": 0,
            "success_total": 0,
            "failure_total": 0,
            "deadline_rejected_total": 0,
            "overload_rejected_total": 0,
            "last_duration_ms": None,
            "last_candidate_count": None,
            "last_payload_chars": None,
        }

    def record_success(self, candidate_count: int, payload_chars: int, duration_ms: int) -> None:
        with self._lock:
            self._data["requests_total"] += 1
            self._data["success_total"] += 1
            self._data["last_duration_ms"] = duration_ms
            self._data["last_candidate_count"] = candidate_count
            self._data["last_payload_chars"] = payload_chars

    def record_failure(self, candidate_count: int, payload_chars: int, duration_ms: Optional[int] = None) -> None:
        with self._lock:
            self._data["requests_total"] += 1
            self._data["failure_total"] += 1
            self._data["last_duration_ms"] = duration_ms
            self._data["last_candidate_count"] = candidate_count
            self._data["last_payload_chars"] = payload_chars

    def record_deadline_rejection(self, candidate_count: int, payload_chars: int, duration_ms: Optional[int] = None) -> None:
        with self._lock:
            self._data["requests_total"] += 1
            self._data["deadline_rejected_total"] += 1
            self._data["last_duration_ms"] = duration_ms
            self._data["last_candidate_count"] = candidate_count
            self._data["last_payload_chars"] = payload_chars

    def record_overload_rejection(self, candidate_count: int, payload_chars: int) -> None:
        with self._lock:
            self._data["requests_total"] += 1
            self._data["overload_rejected_total"] += 1
            self._data["last_candidate_count"] = candidate_count
            self._data["last_payload_chars"] = payload_chars

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._data)


class RerankerRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._rerankers: dict[str, FlagReranker] = {}

    def get(self, model_name: str) -> FlagReranker:
        normalized = normalize_model_name(model_name)
        with self._lock:
            reranker = self._rerankers.get(normalized)
            if reranker is not None:
                return reranker

            kwargs: dict[str, Any] = {
                "query_max_length": DEFAULT_QUERY_MAX_LENGTH,
                "passage_max_length": DEFAULT_PASSAGE_MAX_LENGTH,
                "use_fp16": DEFAULT_USE_FP16,
            }
            if DEFAULT_DEVICE:
                kwargs["devices"] = [DEFAULT_DEVICE]

            reranker = FlagReranker(normalized, **kwargs)
            self._rerankers[normalized] = reranker
            return reranker

    def loaded_models(self) -> list[str]:
        with self._lock:
            return list(self._rerankers.keys())

    def clear(self) -> None:
        with self._lock:
            self._rerankers.clear()


def normalize_model_name(model_name: str) -> str:
    normalized = (model_name or DEFAULT_MODEL).strip()
    if "/" in normalized:
        return normalized
    if normalized.startswith("bge-"):
        return f"BAAI/{normalized}"
    return normalized


def memory_snapshot() -> dict[str, Any]:
    if psutil is None:  # pragma: no cover
        return {"available": False}
    try:
        vm = psutil.virtual_memory()
        process = psutil.Process(os.getpid())
        return {
            "available": True,
            "rss_mb": round(process.memory_info().rss / 1024 / 1024, 1),
            "system_total_mb": round(vm.total / 1024 / 1024, 1),
            "system_available_mb": round(vm.available / 1024 / 1024, 1),
        }
    except Exception as exc:  # pragma: no cover
        return {"available": False, "error": str(exc)}


def parse_preload_models() -> list[str]:
    models = [normalize_model_name(item) for item in PRELOAD_MODELS_ENV.split(",") if item.strip()]
    if not models and PRELOAD_ON_START:
        return [normalize_model_name(DEFAULT_MODEL)]
    return models


def pad_text(seed: str, target_chars: int) -> str:
    repeated = (seed + " ") * max(8, (target_chars // max(1, len(seed))) + 2)
    return repeated[:target_chars]


def build_warmup_documents() -> list[str]:
    if WARMUP_DOCS_ENV.strip():
        if WARMUP_DOCS_ENV.strip().startswith("["):
            try:
                parsed = json.loads(WARMUP_DOCS_ENV)
                if isinstance(parsed, list):
                    docs = [str(item) for item in parsed if str(item).strip()]
                    if docs:
                        return docs
            except json.JSONDecodeError:
                logger.warning("Failed to parse BGE_RERANKER_WARMUP_DOCS as JSON array; falling back to delimiter parsing")
        docs = [item.strip() for item in WARMUP_DOCS_ENV.split("||") if item.strip()]
        if docs:
            return docs

    target = max(DEFAULT_PASSAGE_MAX_LENGTH, 512)
    return [
        pad_text("The employee handbook explains travel reimbursement timelines and approval routing.", target),
        pad_text("The HR policy describes leave carry-over rules, annual leave eligibility, and payroll cutoffs.", target),
    ]


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def extract_deadline_epoch_ms(request: Request) -> Optional[int]:
    raw = request.headers.get(DEADLINE_HEADER)
    if not raw:
        return None
    try:
        return int(raw.strip())
    except ValueError:
        return None


def ensure_not_expired(request: Request) -> None:
    deadline_epoch_ms = extract_deadline_epoch_ms(request)
    if deadline_epoch_ms is None:
        return
    if int(time.time() * 1000) > deadline_epoch_ms:
        raise HTTPException(
            status_code=status.HTTP_408_REQUEST_TIMEOUT,
            detail=f"request deadline exceeded before rerank execution ({DEADLINE_HEADER})",
        )


def configure_runtime_threads() -> None:
    if CPU_THREADS <= 0 or torch is None:
        return
    try:  # pragma: no cover
        torch.set_num_threads(CPU_THREADS)
        logger.info("Configured torch CPU thread count: %s", CPU_THREADS)
    except Exception as exc:
        logger.warning("Failed to set torch CPU thread count: %s", exc)


def run_warmup() -> tuple[int, list[float]]:
    start = time.perf_counter()
    reranker = registry.get(DEFAULT_MODEL)
    docs = build_warmup_documents()
    pairs = [[WARMUP_QUERY, document] for document in docs]
    scores = reranker.compute_score(pairs, normalize=True)
    if isinstance(scores, float):
        scores = [scores]
    if len(scores) != len(docs):
        raise RuntimeError("warmup returned unexpected score count")
    duration_ms = int((time.perf_counter() - start) * 1000)
    return duration_ms, [float(score) for score in scores]


def build_status_payload() -> dict[str, Any]:
    payload = service_status.snapshot()
    payload["loaded_models"] = registry.loaded_models()
    payload["request_stats"] = request_stats.snapshot()
    return payload


registry = RerankerRegistry()
service_status = ServiceStatus()
inflight_semaphore = threading.BoundedSemaphore(MAX_CONCURRENT_REQUESTS)
request_stats = RequestStats()


class LazyBootstrapCoordinator:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._started = False

    def ensure_started(self) -> bool:
        with self._lock:
            if self._started:
                return False
            self._started = True

        thread = threading.Thread(target=self._run, name="bge-reranker-lazy-bootstrap", daemon=True)
        thread.start()
        return True

    def _run(self) -> None:
        started_at = time.perf_counter()
        logger.info("Starting background reranker bootstrap for lazy-loading mode")
        try:
            preload_started = time.perf_counter()
            registry.get(DEFAULT_MODEL)
            preload_duration_ms = int((time.perf_counter() - preload_started) * 1000)
            warmup_duration_ms: Optional[int] = None
            last_warmup_at: Optional[str] = None

            if WARMUP_ENABLED:
                warmup_duration_ms, warmup_scores = run_warmup()
                last_warmup_at = iso_now()
                logger.info(
                    "Background warmup completed: preloadDurationMs=%s, warmupDurationMs=%s, scores=%s",
                    preload_duration_ms,
                    warmup_duration_ms,
                    warmup_scores,
                )

            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "READY",
                "ready",
                preload_duration_ms=preload_duration_ms,
                warmup_duration_ms=warmup_duration_ms,
                warmup_complete=not WARMUP_ENABLED or warmup_duration_ms is not None,
                last_warmup_at=last_warmup_at,
                startup_duration_ms=int((time.perf_counter() - started_at) * 1000),
                last_error=None,
            )
            logger.info(
                "Background reranker bootstrap completed: preloadDurationMs=%s, warmupDurationMs=%s",
                preload_duration_ms,
                warmup_duration_ms,
            )
        except Exception as exc:
            logger.exception("Background reranker bootstrap failed: %s", exc)
            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "FAILED",
                "failed",
                startup_duration_ms=int((time.perf_counter() - started_at) * 1000),
                last_error=str(exc),
            )
        finally:
            with self._lock:
                self._started = False


lazy_bootstrap = LazyBootstrapCoordinator()


@asynccontextmanager
async def lifespan(_: FastAPI):
    startup_started = time.perf_counter()
    configure_runtime_threads()
    preload_models = parse_preload_models()
    logger.info(
        "Starting BGE reranker server: defaultModel=%s, preload=%s, preloadModels=%s, warmup=%s, device=%s, useFp16=%s, queryMax=%s, passageMax=%s, memory=%s",
        normalize_model_name(DEFAULT_MODEL),
        PRELOAD_ON_START,
        preload_models,
        WARMUP_ENABLED,
        DEFAULT_DEVICE or "auto",
        DEFAULT_USE_FP16,
        DEFAULT_QUERY_MAX_LENGTH,
        DEFAULT_PASSAGE_MAX_LENGTH,
        memory_snapshot(),
    )

    try:
        preload_duration_ms: Optional[int] = None
        warmup_duration_ms: Optional[int] = None

        if PRELOAD_ON_START and preload_models:
            preload_start = time.perf_counter()
            for model_name in preload_models:
                registry.get(model_name)
            preload_duration_ms = int((time.perf_counter() - preload_start) * 1000)
            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "INITIALIZING",
                "preloaded",
                preload_duration_ms=preload_duration_ms,
            )

        if WARMUP_ENABLED:
            warmup_duration_ms, warmup_scores = run_warmup()
            logger.info("Warmup completed: durationMs=%s, scores=%s", warmup_duration_ms, warmup_scores)
            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "READY",
                "ready",
                preload_duration_ms=preload_duration_ms,
                warmup_duration_ms=warmup_duration_ms,
                warmup_complete=True,
                last_warmup_at=iso_now(),
                startup_duration_ms=int((time.perf_counter() - startup_started) * 1000),
                last_error=None,
            )
        elif PRELOAD_ON_START:
            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "READY",
                "ready",
                preload_duration_ms=preload_duration_ms,
                warmup_duration_ms=None,
                warmup_complete=not WARMUP_ENABLED,
                startup_duration_ms=int((time.perf_counter() - startup_started) * 1000),
                last_error=None,
            )
        else:
            service_status.set_loaded_models(registry.loaded_models())
            service_status.set_state(
                "DEGRADED",
                "lazy-loading",
                preload_duration_ms=preload_duration_ms,
                warmup_duration_ms=None,
                warmup_complete=False,
                startup_duration_ms=int((time.perf_counter() - startup_started) * 1000),
                last_error="preload disabled; /ready will trigger background bootstrap before becoming ready",
            )
    except Exception as exc:
        logger.exception("Reranker startup failed: %s", exc)
        service_status.set_loaded_models(registry.loaded_models())
        service_status.set_state(
            "FAILED",
            "failed",
            startup_duration_ms=int((time.perf_counter() - startup_started) * 1000),
            last_error=str(exc),
        )

    try:
        yield
    finally:
        logger.info("Shutting down BGE reranker server")
        registry.clear()
        gc.collect()
        if torch is not None and torch.cuda.is_available():  # pragma: no cover
            try:
                torch.cuda.empty_cache()
            except Exception as exc:
                logger.warning("Failed to clear CUDA cache during shutdown: %s", exc)
        service_status.set_loaded_models([])
        service_status.set_state("STOPPED", "stopped", last_error=None)


app = FastAPI(title="BGE Reranker Server", version="2.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, Any]:
    return build_status_payload()


@app.get("/ready")
def ready() -> JSONResponse:
    if service_status.snapshot().get("state") == "DEGRADED":
        started = lazy_bootstrap.ensure_started()
        if started:
            service_status.set_state(
                "INITIALIZING",
                "background-bootstrap",
                warmup_complete=False,
                last_error=None,
            )
    payload = build_status_payload()
    if service_status.is_ready():
        return JSONResponse(status_code=status.HTTP_200_OK, content=payload)
    return JSONResponse(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, content=payload)


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest, http_request: Request) -> RerankResponse:
    if not request.query.strip():
        raise HTTPException(status_code=400, detail="query must not be blank")
    if not request.documents:
        raise HTTPException(status_code=400, detail="documents must not be empty")
    candidate_count = len(request.documents)
    payload_chars = len(request.query) + sum(len(document) for document in request.documents)
    acquired = False
    start = time.perf_counter()

    if not inflight_semaphore.acquire(blocking=False):
        request_stats.record_overload_rejection(candidate_count, payload_chars)
        logger.warning(
            "Rejecting rerank request due to overload: model=%s, candidateCount=%s, payloadChars=%s, maxConcurrent=%s",
            normalize_model_name(request.model or DEFAULT_MODEL),
            candidate_count,
            payload_chars,
            MAX_CONCURRENT_REQUESTS,
        )
        raise HTTPException(status_code=503, detail="reranker is overloaded; try again later")

    acquired = True

    try:
        ensure_not_expired(http_request)

        model_name = normalize_model_name(request.model or DEFAULT_MODEL)
        reranker = registry.get(model_name)
        service_status.set_loaded_models(registry.loaded_models())

        ensure_not_expired(http_request)

        pairs = [[request.query, document] for document in request.documents]
        scores = reranker.compute_score(pairs, normalize=True)
        if isinstance(scores, float):
            scores = [scores]
        if len(scores) != len(request.documents):
            raise HTTPException(status_code=500, detail="reranker returned unexpected score count")

        ranked = sorted(
            enumerate(scores),
            key=lambda item: item[1],
            reverse=True,
        )[: max(1, min(request.top_n, len(request.documents)))]

        results = [
            RerankResult(
                index=index,
                relevance_score=float(score),
                document=request.documents[index] if request.return_documents else None,
            )
            for index, score in ranked
        ]

        if model_name == normalize_model_name(DEFAULT_MODEL) and not service_status.is_ready():
            service_status.set_state(
                "READY",
                "ready",
                warmup_complete=not WARMUP_ENABLED,
                startup_duration_ms=service_status.snapshot().get("startup_duration_ms"),
                last_error=None,
            )
            service_status.set_loaded_models(registry.loaded_models())

        duration_ms = int((time.perf_counter() - start) * 1000)
        request_stats.record_success(candidate_count, payload_chars, duration_ms)
        logger.info(
            "Rerank request completed: model=%s, candidateCount=%s, payloadChars=%s, topN=%s, durationMs=%s",
            model_name,
            candidate_count,
            payload_chars,
            len(results),
            duration_ms,
        )
        return RerankResponse(model=model_name, results=results)
    except HTTPException as exc:
        duration_ms = int((time.perf_counter() - start) * 1000)
        if exc.status_code == status.HTTP_408_REQUEST_TIMEOUT:
            request_stats.record_deadline_rejection(candidate_count, payload_chars, duration_ms)
            logger.warning(
                "Rejecting expired rerank request: model=%s, candidateCount=%s, payloadChars=%s, durationMs=%s",
                normalize_model_name(request.model or DEFAULT_MODEL),
                candidate_count,
                payload_chars,
                duration_ms,
            )
        else:
            request_stats.record_failure(candidate_count, payload_chars, duration_ms)
            logger.warning(
                "Rerank request failed with HTTPException: model=%s, status=%s, candidateCount=%s, payloadChars=%s, durationMs=%s, detail=%s",
                normalize_model_name(request.model or DEFAULT_MODEL),
                exc.status_code,
                candidate_count,
                payload_chars,
                duration_ms,
                exc.detail,
            )
        raise
    except Exception as exc:
        duration_ms = int((time.perf_counter() - start) * 1000)
        request_stats.record_failure(candidate_count, payload_chars, duration_ms)
        logger.exception(
            "Rerank request failed: model=%s, candidateCount=%s, payloadChars=%s, durationMs=%s, error=%s",
            normalize_model_name(request.model or DEFAULT_MODEL),
            candidate_count,
            payload_chars,
            duration_ms,
            exc,
        )
        raise
    finally:
        if acquired:
            inflight_semaphore.release()


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=DEFAULT_HOST, port=DEFAULT_PORT)
