# OntoFlow AI Architecture

## Phase 1 runtime

```text
Browser (:3000)
  |-- Platform API / Spring Boot (:8080) -- PostgreSQL (:5432)
  |                                      `- Redis (:6379)
  |-- AI Service / FastAPI (:8000)
  `-- Mock Enterprise API (:8090)

Semantic store: Jena Fuseki (:3030)
Automation runtime: n8n (:5678)
```

The Spring Boot service is the future policy-enforcement boundary. The FastAPI
service owns AI orchestration and analysis. Enterprise connectors are represented
by the replaceable mock service in Phase 1. Fuseki and n8n are provisioned but no
business workflow is introduced before its designated phase.

## Cross-cutting conventions

- Every HTTP service accepts or creates `X-Trace-Id` and returns it to callers.
- Application logs are structured JSON or JSON-shaped records and name the service.
- Secrets are injected through environment variables and `.env` is ignored.
- Compose health checks control startup dependencies.
- PostgreSQL schema changes are owned by Flyway.
- Development data resides in named Docker volumes.

## Profiles

- `local`: containerized dependencies and developer-safe defaults.
- `test`: isolated application configuration; Spring uses in-memory H2.
- `production`: reserved for deployment phases and intentionally has no defaults.
- Compose profile `test`: disposable test runners only.

