# OntoFlow AI

Ontology-driven multi-agent enterprise automation platform. Phase 1 provides a
fully containerized, tested development foundation without introducing business
features assigned to later phases.

## Prerequisites

- Docker Desktop with Compose v2
- Optional for host development: Node.js 24, Java 21, Python 3.12 and `uv`

## Start locally

```powershell
Copy-Item .env.example .env
# Replace every change-me value in .env before starting.
docker compose up --build
```

Compose checks required variables before containers start. If a secret is
missing, startup stops with the exact variable name instead of using an unsafe
empty value.

| Component | URL |
|---|---|
| Web | http://localhost:3000 |
| Platform health | http://localhost:8080/actuator/health |
| Platform Swagger | http://localhost:8080/docs |
| AI health / Swagger | http://localhost:8000/health · http://localhost:8000/docs |
| Mock Enterprise | http://localhost:8090/health |
| Fuseki | http://localhost:3030 |
| n8n | http://localhost:5678 |

Stop services with `docker compose down`. Named volumes preserve local data;
`docker compose down -v` intentionally removes it.

## Test and validate

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example --profile test run --rm platform-api-test
docker compose --env-file .env.example --profile test run --rm ai-service-test
docker compose --env-file .env.example --profile test run --rm web-test
docker run --rm -v "${PWD}/apps/mock-enterprise:/app" -w /app node:24.11.0-alpine node --test
```

The GitHub Actions workflow runs the same language-level checks and validates
YAML/JSON plus the resolved Compose model.

## Repository layout

```text
apps/web              Next.js product shell
apps/platform-api     Spring Boot policy/API boundary
apps/ai-service       FastAPI AI service
apps/mock-enterprise  Replaceable enterprise-system sandbox
docs                  Architecture, API contracts and phase reports
compose.yaml          Local runtime and test runners
```

See [architecture](docs/ARCHITECTURE.md), [API contracts](docs/API_CONTRACTS.md),
and the [Phase 1 report](docs/phases/PHASE_01.md).

