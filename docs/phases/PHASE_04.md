# PHASE 4 IMPLEMENTATION REPORT

## 1. 구현 요약

Phase 3 Alpha/Beta Mock 커넥터를 유지하면서 PostgreSQL/MySQL 읽기 전용 View, GitHub App 설치 Repository, Slack OAuth Bot 채널을 서버가 허용한 리소스 키로 연결한다. 연결·조회·동기화·해제 API와 관리자 화면을 추가했다.

## 2. 새로 추가된 기능

- Tenant별 서버 리소스 허용 목록과 DB/GitHub/Slack Registry, `source.read` Capability
- DB의 서버 지정 View에만 `SELECT`/정렬/페이지 제한 적용. 쿼리 시간 5초, 페이지 최대 100개. 사용자 SQL·DB URL·테이블 이름 입력 경로 없음
- GitHub App 설치 URL, 사용자 OAuth를 통한 설치 Repository 소유권 확인, Repository/Issue/PR/Commit 조회. 설치 토큰은 단일 Repository와 read 권한으로 축소
- Slack OAuth state 검증, 채널 유형별 scope 요청, Bot 멤버십 확인, 메시지 조회, 만료 토큰 갱신 및 해제 시 토큰 회수 시도. DM은 허용하지 않음
- Credential 참조와 AES-GCM 암호문 분리, 교체·연결 검사·오류 상태
- 페이지별 체크포인트 커밋, 동기화 임대 잠금, 20페이지/회 제한, 변경·삭제 이벤트와 출처 메타데이터
- 권한 회수·원본 삭제·Capability 중단·연결 해제 시 수집 본문 비노출과 삭제 이벤트
- `/dashboard/{tenant}/sources` 화면에서 연결, 동기화, 상태, 출처와 삭제 상태 조회

## 3. 수정·추가 파일

`apps/platform-api/src/main/java/ai/ontoflow/platform/connector/`, `apps/platform-api/src/main/java/db/migration/`, `apps/platform-api/src/main/resources/db/migration/V4__enterprise_sources.sql`, `apps/web/app/dashboard/[tenant]/sources/page.tsx`, Compose·환경 설정·테스트·API/아키텍처 문서.

## 4. API 및 데이터베이스 변경

[API 계약](../API_CONTRACTS.md)의 Phase 4 절을 참조한다. `connector_registry`, `connector_credentials`, `source_records`, `source_events`, `source_sync_state`, `source_oauth_states`를 추가한다. GitHub/Slack 설치 ID·토큰을 URL이나 로그에 기록하지 않는다. `source_events`는 후속 RDF/RAG 파생 데이터 정리를 위한 입력 계약이다.

## 5. 사용 기술

Spring Boot/JDBC, PostgreSQL/MySQL 드라이버, Java HttpClient, GitHub App JWT 및 OAuth, Slack OAuth, Flyway, AES-256-GCM, Next.js.

## 6. 실행 방법

1. `.env.example`을 복사하고 기본 인프라 비밀값을 설정한다.
2. 외부 DB 계정에는 승인된 View의 `SELECT`만 부여한다. View에는 안정적인 고유 ID 컬럼이 필요하다. PostgreSQL 계정의 View INSERT/UPDATE/DELETE/TRUNCATE 권한과 MySQL 계정의 쓰기 Grant는 연결 검사에서 거절한다. 기본 설정은 PostgreSQL `sslmode=verify-full` 또는 MySQL `sslMode=VERIFY_IDENTITY`를 JDBC URL에 요구한다.
3. GitHub App에는 Repository Metadata, Contents, Issues, Pull requests의 읽기 권한을 부여하고 설치 대상 Repository를 제한한다. App ID, PKCS#8 개인키의 base64, Client ID/Secret, App slug와 OAuth callback URL을 환경 변수로 설정한다. 사용자는 GitHub App을 설치한 뒤 설치 ID를 입력하고 사용자 OAuth에 동의한다.
4. Slack App에는 사용할 공개 채널의 `channels:read,channels:history` 또는 비공개 채널의 `groups:read,groups:history`를 설정한다. Bot을 해당 채널에 초대한다. Client ID/Secret과 OAuth callback URL을 환경 변수로 설정한다. DM scope는 요청하지 않는다.
5. `SOURCE_CATALOG_JSON`에 서버 허용 리소스를 지정한다. 예시:

```json
{"ontoflow":{"connector":{"resources":{"billing":{"type":"POSTGRESQL","tenant-id":"TENANT_UUID","jdbc-url":"jdbc:postgresql://host:5432/app?sslmode=verify-full","view":"reporting.approved_view","id-column":"id"},"orders":{"type":"MYSQL","tenant-id":"TENANT_UUID","jdbc-url":"jdbc:mysql://host:3306/app?sslMode=VERIFY_IDENTITY","view":"reporting_orders","id-column":"id"},"engineering":{"type":"GITHUB","tenant-id":"TENANT_UUID","repository":"org/repo"},"support":{"type":"SLACK","tenant-id":"TENANT_UUID","channel-id":"C123456789"}}}}}
```

6. `docker compose --env-file .env up --build` 후 데이터 원천 화면에서 연결한다. `tenant-id`를 지정해 리소스 키를 Tenant에 묶는다. 실제 운영에서는 HTTPS, 제한된 네트워크와 비밀 저장소를 사용한다.

## 7. 테스트 결과

- `docker build --target build -f apps/web/Dockerfile apps/web`: 통과
- `docker run --rm ontoflow-web-phase4-check pnpm lint`: 통과
- `docker compose --env-file .env.example --profile test run --build --rm web-test`: lint, Vitest 9개, Next.js build 통과
- `docker compose --env-file .env.example config --quiet`: 통과
- `docker compose --env-file .env.example --profile test run --build --rm platform-api-test`: 통과. H2 마이그레이션, Mock 커넥터 회귀, Tenant 격리, OAuth state, 동기화 재개·삭제와 SourceReader 입력 정책 포함
- 임시 PostgreSQL 17.6 컨테이너에서 API BootJar 실행: Flyway 5개 마이그레이션 적용, `/actuator/health`가 `UP` 반환. 검증 후 임시 컨테이너 종료

## 8. 보안 및 Tenant 격리 영향

서버 리소스 카탈로그, 연결·Credential·Cursor·출처 행에 Tenant 경계를 적용한다. API는 로그인 사용자의 실제 멤버십을 재검증한다. 등록/검사/동기화/Capability/해제는 관리자 전용이다. 외부 원천에는 쓰기 API가 없으며 GitHub/Slack API URL은 코드에서 고정한다. OAuth state는 해시 저장, 10분 만료, 단일 사용이다. 감사 이벤트에는 수행자·Tenant·연결 ID·trace ID를 남기며 토큰과 메시지 본문은 남기지 않는다.

## 9. Known Issues

- 실제 기업 PostgreSQL/MySQL·GitHub App·Slack 계정이 제공되지 않아 공급자 연결 E2E는 아직 검증하지 못했다. 공급자 실패·권한 변경 경로는 Mock Reader 기반 통합 테스트로 확인한다.
- DB 동기화는 안정적인 ID 정렬의 승인 View를 전제로 한다. 외부 데이터가 스캔 중 변하면 offset 페이지에서 누락 가능성이 있다. 운영 적용에는 변경 시각/키셋 기반 증분 계약을 원천별로 설계해야 한다.
- Slack Bot 토큰 회수 호출이 일시 실패해도 로컬 Credential과 수집 본문은 즉시 제거한다. 공급자 측 회수 상태는 운영자가 확인해야 한다.
- GitHub/Slack 비정형 검색과 RDF/RAG 파생 데이터 실제 삭제 소비자는 각각 후속 Phase의 범위다.

공급자 권한 계약은 [GitHub App 설치 토큰](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app), [GitHub 사용자 설치 접근 확인](https://docs.github.com/en/rest/apps/installations), [Slack 채널 메시지 권한](https://docs.slack.dev/reference/methods/conversations.history/), [Slack 토큰 회수](https://docs.slack.dev/reference/methods/auth.revoke/)의 공식 문서를 기준으로 한다.

## 10. 다음 Phase를 위한 준비 상태

Phase 5 MCP는 `source.read` Capability와 Tenant 검사를 재사용할 수 있다. Phase 7·10은 `source_events`의 DELETE를 소비해 파생 데이터 비노출을 구현해야 한다.
