# API Contracts

## Operational endpoints

| Service | Endpoint | Contract |
|---|---|---|
| Web | `GET /api/health` | `{ "status": "ok", "service": "web" }` |
| Platform API | `GET /actuator/health` | Spring Actuator health response |
| Platform API | `GET /api/v1` | Phase 1 API marker |
| AI Service | `GET /health` | `{ "status": "ok", "service": "ai-service" }` |
| Mock Enterprise | `GET /health` | `{ "status": "ok", "service": "mock-enterprise" }` |

Spring OpenAPI is served at `/v3/api-docs` and Swagger UI at `/docs`.
FastAPI OpenAPI is served at `/openapi.json` and Swagger UI at `/docs`.

All application HTTP responses include `X-Trace-Id`. Incoming values are accepted
only when they match `[A-Za-z0-9._-]{1,64}`; otherwise the service creates one.

