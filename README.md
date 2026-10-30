# OntoFlow AI

Ontology-driven multi-agent enterprise automation platform. Phase 1 provides a
containerized development foundation. Phase 2 adds JWT authentication, rotating
refresh sessions, isolated workspaces, member roles and audit events. Phase 3
adds Alpha/Beta mock enterprise systems and tenant-scoped connectors.

## Prerequisites

- Docker Desktop with Compose v2
- Optional for host development: Node.js 24, Java 21, Python 3.12 and `uv`

## Start locally

```powershell
Copy-Item .env.example .env
# Replace every change-me value in .env before starting.
# JWT_SECRET must be a random secret of at least 32 bytes.
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
and the [Phase 3 report](docs/phases/PHASE_03.md).

## Connect enterprise systems

Open a workspace and choose **연결 시스템**. OWNER or ADMIN can add Alpha or
Beta using the corresponding `ALPHA_MOCK_TOKEN` or `BETA_MOCK_TOKEN` from
the local `.env`. The API verifies the credential before storing it. The
workspace page shows connection status, available capabilities and normalized
failed payments. Other members can read connected system data; only OWNER and
ADMIN can change connections and capabilities.

Set a unique random `CONNECTOR_ENCRYPTION_KEY` of at least 32 characters in
`.env`. The local secret store encrypts credentials using AES-256-GCM before
writing them to PostgreSQL. Losing or changing this key makes saved credentials
unreadable. Production can replace the `SecretStore` implementation with
Secrets Manager.

## Use workspaces

Open `/register`, create an account, then create a workspace at `/workspaces`.
Register another account before adding it as a workspace member by email.
OWNER and ADMIN manage members; only OWNER can grant or modify ADMIN/OWNER.
The final OWNER cannot be removed or demoted. OPERATOR and VIEWER can open
their workspace but cannot manage members or read audit logs.

The browser keeps only the 15-minute access token in memory. A host-only HttpOnly
refresh cookie restores login after reload and rotates on use, with an absolute
seven-day login lifetime. Cookie-based auth mutations require a CSRF token.
Redis shares login rate limits and validates each access token's login family;
logout/replay immediately blocks further access for that family. PostgreSQL
retains refresh-token hashes and audit records. Redis outages return 503 rather
than bypassing authentication. Every workspace request checks current membership.

Local HTTP uses `AUTH_COOKIE_SECURE=false` in `.env.example`. Production requires
`AUTH_COOKIE_SECURE=true`, an exact HTTPS `WEB_ORIGIN`, and HTTPS for the API.
Web/API must be on the same site for SameSite=Strict cookies. Existing `.env`
files need the local cookie setting added explicitly. See
[authentication deployment and behavior](docs/AUTH_SECURITY.md).

## Phase 4 external sources

Workspace administrators can open **데이터 원천** to connect server-approved read-only DB views, GitHub App installations, or Slack channels. Configure allowed resource keys and provider credentials first; the setup and API contract are in [Phase 4 report](docs/phases/PHASE_04.md). Existing Alpha/Beta Mock connections remain under **연결 시스템**.

