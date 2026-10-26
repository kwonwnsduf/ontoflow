import logging
import re
import secrets
import time

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.config import get_settings
from app.logging_config import configure_logging

settings = get_settings()
configure_logging(settings.log_level)
logger = logging.getLogger(__name__)
TRACE_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

app = FastAPI(
    title="OntoFlow AI Service",
    description="AI orchestration and analysis API",
    version="0.1.0",
)


@app.middleware("http")
async def trace_and_log(request: Request, call_next):
    supplied = request.headers.get("X-Trace-Id", "")
    trace_id = supplied if TRACE_PATTERN.fullmatch(supplied) else secrets.token_hex(16)
    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception:
        logger.exception("request_failed", extra={"trace_id": trace_id})
        response = JSONResponse(status_code=500, content={"detail": "Internal server error"})
    response.headers["X-Trace-Id"] = trace_id
    logger.info(
        "request_completed",
        extra={
            "trace_id": trace_id,
            "method": request.method,
            "path": request.url.path,
            "status_code": response.status_code,
            "duration_ms": round((time.perf_counter() - started) * 1000, 2),
        },
    )
    return response


@app.get("/health", tags=["operations"])
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "ai-service"}

