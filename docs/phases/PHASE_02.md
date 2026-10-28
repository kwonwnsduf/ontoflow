# 2단계 구현 보고서

## 1. 구현 요약

인증, 워크스페이스 격리, 멤버 역할 관리, 감사 이벤트와 프론트엔드 인증 흐름을 구현했다.
3단계 이후 커넥터·온톨로지·에이전트 기능은 추가하지 않았다.

## 2. 새로 추가한 기능

- 회원가입, BCrypt 해싱, 로그인, 15분 JWT, 7일 갱신 토큰 회전과 로그아웃.
- 갱신 토큰 SHA-256 해시 저장, 만료·재사용 감지 및 로그인 묶음 폐기.
- 워크스페이스 생성·목록·전환, 현재 멤버십에 기반한 TenantContext.
- OWNER/ADMIN/OPERATOR/VIEWER 역할, 관리자 권한 보호, 마지막 OWNER 보호.
- 로그인·회원가입·워크스페이스 선택·보호된 대시보드·멤버 관리 화면.
- 페이지네이션, 유효성 검사, 요청 추적 번호, 공통 오류, 감사 로그.

## 3. 수정/추가 파일

`apps/platform-api/.../{auth,user,tenant,audit,common}/` 도메인·서비스·컨트롤러·보안·오류 처리,
`V2__identity_and_workspaces.sql`, `IdentityIntegrationTest.java`,
`apps/web/app/{login,register,workspaces,dashboard,lib}` 및 인증 폼 테스트.
환경 예제·Compose·Gradle 의존성 잠금 파일·CI·관련 문서를 갱신했다.

## 4. API 및 데이터베이스 변경

`docs/API_CONTRACTS.md` 참조. V2에서 users, tenants, tenant_members,
refresh_tokens, audit_logs를 생성한다. V1은 유지한다.

## 5. 사용 기술

Spring Boot 3.5.7, Spring Security, JJWT 0.13.0, JPA, Flyway, PostgreSQL 17,
Next.js, React, TypeScript, JUnit/MockMvc, Vitest/Testing Library.

## 6. 실행 방법

`.env.example`을 `.env`로 복사하고 비밀값을 변경한다. JWT_SECRET은 최소
32바이트의 무작위 값으로 지정한 후 `docker compose up --build`를 실행한다.
`http://localhost:3000/register`에서 계정을 만들고 워크스페이스를 생성한다.
새 멤버는 먼저 계정을 등록한 후 이메일로 추가한다.

## 7. 테스트 결과

검증 환경: Windows 호스트와 Docker Linux 컨테이너, Java 21/Gradle 8.14.3.

| 검사 | 명령 | 결과 |
|---|---|---|
| 백엔드 H2 + Redis | Gradle `test bootJar --no-daemon` | 21개 통과, 실행 JAR 생성 성공 |
| 백엔드 PostgreSQL 17.6 + Redis | Gradle `test --rerun-tasks --no-daemon`, PostgreSQL DB 연결 환경변수 지정 | 21개 통과 |
| 프론트엔드 정적 검사 | `corepack pnpm lint` | 통과 |
| 프론트엔드 테스트 | `corepack pnpm test` | 9개 통과 |
| 프론트엔드 빌드 | `corepack pnpm build` | 통과 |
| AI 서비스 | Docker 테스트 단계: `uv run ruff check . && uv run pytest` | 정적 검사 및 3개 통과, 테스트 커버리지 93.18% |
| 모의 API | Docker Node 24: `node --test` | 1개 통과 |
| CI 형식 검사 | `npx --yes prettier@3.6.2 --check "**/*.{json,yml,yaml}"` | 통과 |
| Compose | `docker compose --env-file .env.example config --quiet` | 통과 |
| 공백 오류 | `git diff --check` | 통과 |
| 실행 JAR 검증 | PostgreSQL 연결 후 `java -jar` + HTTP 요청 | `/api/v1` 200, 미인증 `/tenants` 401, 요청 추적 번호와 캐시 금지 헤더 확인 |

PostgreSQL 17.6에서 Flyway V1·V2의 성공 적용을 확인했다.

백엔드 테스트는 정상/잘못된 로그인, 해싱, JWT 만료·변조, 갱신 만료·회전·재사용,
로그인 묶음별 폐기, 로그아웃, 테넌트 교차 접근·헤더 위조, 역할별 접근·승격 차단,
마지막 OWNER 보호, 멤버 제거 후 접근 차단과 요청 추적 번호를 검증한다.
프론트엔드는 로그인 성공, 회원가입 오류, 동시 요청의 갱신 단일 회전,
갱신 실패 시 세션 제거와 로그아웃을 검증한다.

초기 검사에서 발견한 H2 TIMESTAMPTZ 호환 문제 및 React 효과 처리 정적 검사 오류를
수정한 뒤 관련 검사를 재실행했다. GitHub 원격 실행은 푸시 전이므로 수행하지 않았다.

## 8. 보안 및 테넌트 격리 영향

서명된 JWT의 사용자 ID와 DB 멤버십으로 권한을 결정한다. 클라이언트의
테넌트 ID나 역할을 신뢰하지 않는다. 멤버 삭제·역할 변경은 다음 API 요청부터
적용된다. 갱신 회전은 사용자 행 잠금, 멤버 변경은 테넌트 행 잠금으로
동시 요청을 직렬화한다. 감사 로그에 비밀번호나 토큰을 기록하지 않는다.

## 9. 알려진 제약

로그인 유지 및 로그아웃 정책은 이후 쿠키·CSRF·Redis 보완으로 변경했다.
현재 흐름과 운영 조건은 `docs/AUTH_SECURITY.md`를 참조한다.
이메일 초대 발송·비밀번호 재설정은 이번 단계 범위에 포함되지 않는다.

## 10. 다음 단계 준비 상태

### 백엔드 패키지 및 가독성 리팩터링

기능별 `auth`, `user`, `tenant`, `audit` 아래에 `controller`, `domain`, `dto`,
`repository`, `service`를 나눴다. 공통 보안·오류·요청 추적·설정·페이지네이션은
`common`으로 이동했다. 상세 파일 구조와 의존 규칙은 `docs/ARCHITECTURE.md`를 참조한다.

기존 IdentityService의 DB 쿼리는 리포지토리로 이동하고 인증·토큰 발급·멤버십
검증·멤버 관리·감사 기록/조회 서비스를 분리했다. API 경로와 JSON 및 DB 스키마는
유지했다. 엔티티와 DTO를 파일별로 분리하고, 엔티티 상태를 캡슐화했다.
컨트롤러에는 구체적인 응답 타입을 명시했으며, Java 소스와 테스트는
google-java-format 1.24.0의 AOSP 스타일(4칸 들여쓰기)로 정리했다.

갱신 토큰 검증을 별도 서비스로 이동하면서도 재사용 감지 시 폐기가
커밋되도록 트랜잭션 예외 규칙을 유지했다. 등록되지 않은 갱신 토큰에 대한
401 회귀 검증을 기존 통합 테스트에 추가했다.

리팩터링 최종 소스로 H2 및 PostgreSQL 17.6 통합 테스트를 각각 5개 모두
통과했고, `bootJar` 생성과 `git diff --check`도 통과했다. 검증 중 공유 Gradle
캐시 잠금으로 한 번 시작하지 못한 실행은 앞선 실행 종료 후 순차 재실행했다.

### Spring Data JPA 리포지토리 전환

리포지토리 5개를 `JpaRepository<Entity, UUID>` 인터페이스로 전환했다.
CRUD는 기본 메서드, 조회·개수·정렬은 파생 쿼리, 비관적 잠금은 `@Lock`,
갱신 토큰 묶음 일괄 폐기는 `@Query`와 `@Modifying`을 사용한다.
잠금 획득 후 갱신 토큰의 최신 상태를 다시 읽는 `EntityManager.refresh()`만
커스텀 리포지토리 확장 구현에 남겼다. API·스키마와 서비스 트랜잭션 경계는 유지했다.

페이지네이션의 정렬·전체 개수·빈 페이지, 동시 갱신 요청 중복 차단 및
발급된 로그인 묶음 폐기를 확인하는 통합 테스트를 추가했다.

전환 후 `gradle test bootJar --no-daemon`과 PostgreSQL DB 연결 설정을 지정한
`gradle test --rerun-tasks --no-daemon`을 순차 실행했다. H2·PostgreSQL 17.6에서
각각 7개 테스트가 모두 통과했고, 실행 JAR 생성과 `git diff --check`도 통과했다.

서버에서 검증한 TenantContext, 역할 검사, 감사 이벤트와 마이그레이션 기반을
후속 커넥터 기능에서 사용할 수 있다.

## CI 수정

### JJWT 발급·검증으로 교체

JWT 구현을 JJWT 0.13.0으로 교체하고 OAuth2 리소스 서버 의존성을 제거했다.
`JwtTokenProvider`가 HS256 토큰 발급·파싱을 담당하고,
`JwtAuthenticationFilter`가 Bearer 요청과 Redis 로그인 상태를 검증한다.
인증 정보는 `AuthPrincipal`로 컨트롤러에 전달한다.

기존 서명 키의 UTF-8 해석 방식과 사용자·로그인 묶음·토큰 번호, 발급자,
발급·만료 시각, 60초 오차 허용을 유지했다. 쿠키·CSRF·갱신·폐기 흐름도 유지했다.
만료·다른 발급자·서명 없는 토큰·다른 서명 키·필수 값 누락·잘못된 식별자·
미래 발급 시각·다른 알고리즘·짧은 키·잘못된 토큰 형식을 검증하는 테스트를 추가했다.
실제 Redis를 사용하는 H2·PostgreSQL에서 각각 21개 테스트와 실행 JAR 생성이 통과했다.

### 쿠키 인증 및 Redis 보완

- 갱신 토큰을 JSON에서 제거하고 HttpOnly·SameSite=Strict 쿠키로 전달한다.
  운영은 Secure·__Host 접두사·호스트 전용·Path=/를 사용하며, 로컬 HTTP만 명시적으로
  Secure를 해제한다. 쿠키 수명은 로그인 시점의 7일 만료까지로 고정했다.
- CSRF 쿠키·마스킹 토큰과 인증 정보를 포함한 요청의 정확한 CORS 출처 검증을 추가했다.
- Redis Lua로 계정·IP 로그인 요청 제한과 등록·갱신 요청 제한을 구현했다.
- JWT sid와 Redis 로그인 묶음 상태를 검증해 로그아웃·재사용 감지 시 기존 접근 토큰 전체를 차단한다. Redis 장애는 503, 유실된 세션 상태는 401을 반환한다.
- 갱신 실패의 DB 롤백과 명시적인 재사용 거절의 폐기 커밋을 구분했다.
- 브라우저 세션 복원, 동일 탭의 단일 갱신, Web Locks를 통한 탭 간 직렬화,
  로그아웃 중 갱신 응답의 세션 부활 방지를 구현했다.
- 만료 후 7일 지난 갱신 기록은 매시간 정리한다. Redis는 AOF와
  `noeviction` 정책을 사용하며 CI/Compose 테스트도 Redis를 실행한다.

검증: 실제 Redis를 사용한 H2·PostgreSQL 테스트 각각 15개, 프론트엔드
정적 검사·테스트 9개·빌드, 실행 JAR 생성이 통과했다. 실제 실행 JAR에 HTTP로
CSRF 초기화, 쿠키 로그인/복원, 로그아웃한 JWT 차단, 로그인 429 및
Retry-After 응답도 확인했다. 보안 테스트는 CSRF/CORS 거부, 쿠키 속성,
Redis 장애·유실, 실패한 Redis 활성화의 DB 롤백을 포함한다.
추가로 갱신 쿠키가 없는 경우에도 JWT의 로그인 묶음 번호로 로그아웃하는 백엔드 경로와,
만료된 접근 토큰을 제외하고 쿠키 로그아웃을 재시도하는 프론트엔드 경로를 검증했다.

운영 설정과 남은 기능은 `docs/AUTH_SECURITY.md`를 참조한다. 이메일 인증·
비밀번호 복구·MFA·기기별 세션 관리 화면은 이번 보완 범위에 포함되지 않는다.

기존 실패 실행: https://github.com/kwonwnsduf/ontoflow/actions/runs/40348610450

repository-validation의 Prettier 검사에서 application-local.yml과
pnpm-lock.yaml의 형식이 달라 실패했다. 동일 버전 Prettier 3.6.2로 포맷을
수정하고 생성물 경로를 제외했다. 플랫폼 CI에 실제 PostgreSQL을 사용하는
마이그레이션·인증·테넌트 격리 테스트를 추가했다.
