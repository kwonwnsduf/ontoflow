FROM ghcr.io/astral-sh/uv:0.9.7-python3.12-alpine AS base
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY app ./app

FROM base AS test
RUN uv sync --frozen
COPY tests ./tests
CMD ["sh", "-c", "uv run ruff check . && uv run pytest"]

FROM base AS runtime
RUN addgroup -S ontoflow && adduser -S ontoflow -G ontoflow && chown -R ontoflow:ontoflow /app
USER ontoflow
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
