# OntoFlow AI 아키텍처

## 1단계 실행 구성

```text
브라우저 (:3000)
  |-- 플랫폼 API / Spring Boot (:8080) -- PostgreSQL (:5432)
  |                                      └─ Redis (:6379)
  |-- AI 서비스 / FastAPI (:8000)
  └-- 모의 기업 시스템 API (:8090)

의미 데이터 저장소: Jena Fuseki (:3030)
자동화 실행 환경: n8n (:5678)
```

Spring Boot는 플랫폼의 권한·정책 검증을 담당하고, FastAPI는 AI 실행 흐름과 분석을 담당한다.
1단계에서는 기업 연동 대상을 교체 가능한 모의 서비스로 제공한다.
Fuseki와 n8n은 실행 환경만 준비하며, 해당 구현 단계 전에는 업무 흐름을 추가하지 않는다.

## 공통 규칙

- 각 HTTP 서비스는 `X-Trace-Id`를 받거나 생성한 후 응답에 포함한다.
- 애플리케이션 로그는 서비스 이름이 있는 구조화된 JSON 형식으로 기록한다.
- 비밀값은 환경변수로 주입하며 `.env`는 Git에 포함하지 않는다.
- Compose 상태 확인을 통해 서비스 시작 순서를 제어한다.
- PostgreSQL 스키마 변경은 Flyway로 관리한다.
- 개발 데이터는 이름이 지정된 Docker 볼륨에 저장한다.

## 실행 프로필

- `local`: 컨테이너 의존 서비스와 로컬 개발 설정을 사용한다.
- `test`: 테스트 설정을 사용한다. 기본 DB는 메모리 H2이며 실제 Redis에 연결한다. DB 연결 설정을 바꿔 PostgreSQL에서도 검증한다.
- `production`: 배포 단계를 위한 프로필이다. 별도의 운영 기본값을 제공하지 않는다.
- Compose의 `test`: 검증용 일회성 실행 컨테이너를 활성화한다.

## 2단계 백엔드 패키지 구조

기능별로 패키지를 나누고, 각 기능 안에서 역할별로 구분한다.

```text
ai.ontoflow.platform
├── auth
│   ├── controller   AuthController
│   ├── domain       RefreshToken
│   ├── dto          로그인·회원가입 요청, TokenResponse
│   ├── repository   RefreshTokenRepository
│   └── service      AuthService, TokenService, AuthStateStore, RefreshTokenCleanup
├── user
│   ├── controller   UserController
│   ├── domain       User
│   ├── dto          UserResponse
│   ├── repository   UserRepository
│   └── service      UserService
├── tenant
│   ├── controller   TenantController, TenantMemberController
│   ├── domain       Tenant, TenantMember, Role, TenantContext
│   ├── dto          워크스페이스·멤버 요청과 응답
│   ├── repository   TenantRepository, TenantMemberRepository
│   └── service      TenantService, TenantMemberService, TenantAccessService
├── audit
│   ├── controller   AuditController
│   ├── domain       AuditLog
│   ├── dto          AuditResponse
│   ├── repository   AuditLogRepository
│   └── service      AuditEventService, AuditQueryService
└── common
    ├── config       OpenAPI와 플랫폼 설정
    ├── dto          PageRequest, PageResponse
    ├── error        공통 API 오류 처리
    ├── security     SecurityConfig, JwtTokenProvider, JwtAuthenticationFilter,
    │                AuthPrincipal, SecurityErrorHandler, CurrentUser
    ├── trace        TraceIdFilter
    └── web          RootController
```

### 계층별 책임

| 패키지 | 책임 |
|---|---|
| `controller` | 요청 검증, 서비스 호출, 명시적인 응답 DTO 반환 |
| `domain` | 엔티티·역할·상태와 상태 변경 동작 |
| `dto` | 요청·응답 데이터 형식 |
| `repository` | DB 조회·저장·삭제·잠금 |
| `service` | 업무 정책, 권한 검증 흐름, 트랜잭션 |

리포지토리는 Spring Data JPA의 `JpaRepository`를 상속한다.
기본 생성·조회·수정·삭제는 상속 메서드를 사용하고, 조건 조회·개수·페이지 목록은 파생 쿼리를 사용한다.
기존 정렬 순서는 유지한다.

`@Lock`으로 사용자·테넌트 행의 비관적 쓰기 잠금을 적용한다.
갱신 토큰 묶음을 폐기하는 `@Modifying` 쿼리는 대기 중인 변경을 먼저 DB에 반영한다.
사용자 잠금 후 토큰의 최신 상태를 다시 읽는 `EntityManager.refresh()`만
`RefreshTokenRepositoryCustom` 구현에 남겼다.

API의 페이지 요청을 Spring Data의 `Pageable`로 변환하되,
Spring 내부의 페이지 응답 형식을 클라이언트에 노출하지 않는다.
엔티티와 DTO는 각각 별도 파일로 작성한다.
엔티티 필드는 비공개로 두고, 토큰 소비·멤버 역할 변경은 의미가 드러나는 도메인 메서드로 처리한다.

### 서비스 의존 관계

`TenantAccessService`가 멤버십·역할 검증을 담당한다.
`AuditEventService`는 테넌트 서비스에 의존하지 않고 호출자의 트랜잭션 안에서 감사 기록을 저장한다.
`AuditQueryService`는 테넌트 관리자 권한을 검증한 뒤 기록을 조회한다.
이렇게 기록과 조회를 분리해 테넌트 변경과 감사 기록 사이의 순환 의존을 방지한다.

패키지 리팩터링에서는 API 경로·응답 JSON·엔티티 이름·DB 스키마를 유지했다.
Java 소스와 테스트는 4칸 들여쓰기, 문장별 줄바꿈, 명시적인 운영 코드 import,
여러 줄 메서드 체인 형식으로 정리했다.

## 인증과 워크스페이스 격리

`JwtTokenProvider`가 JJWT로 HS256 JWT를 발급하고 서명·발급자·만료를 검증한다.
`JwtAuthenticationFilter`가 검증된 토큰을 `AuthPrincipal`로 변환한 뒤
Spring Security 인증 정보에 등록한다. 서명 없는 토큰, 다른 알고리즘,
필수 식별자·발급 시각·만료 시각이 없는 토큰은 거절한다.
JWT의 `subject`는 사용자를 식별하고, DB 멤버십·역할은 워크스페이스 작업의 권한을 결정한다.
클라이언트가 보낸 헤더나 본문만으로 테넌트 권한을 부여하지 않는다.
`TenantContext`에는 검증한 사용자·워크스페이스·역할을 담는다.

JPA는 사용자·테넌트·멤버십·갱신 토큰 해시·감사 기록을 저장한다.
Flyway V2에서 해당 테이블을 추가하며 V1은 변경하지 않는다.
갱신 요청은 사용자 행을 잠가 토큰 회전·묶음 폐기를 순서대로 처리한다.
재사용·만료를 거절할 때는 401을 반환해도 폐기 기록을 커밋한다.
멤버 변경은 테넌트 행을 잠가 동시 요청에서도 마지막 OWNER를 보호한다.

### 브라우저 인증

Next.js는 보호된 대시보드 레이아웃을 제공하며, 최종 권한 검증은 서버에서 수행한다.
접근 토큰만 자바스크립트 메모리에 보관한다.
갱신 토큰은 HttpOnly·Secure·SameSite=Strict 속성의 호스트 전용 쿠키로 전달하며,
최초 로그인부터 7일의 절대 유효기간을 적용한다.

새로고침하면 쿠키로 로그인을 복원한다.
지원 브라우저에서는 Web Locks로 여러 탭의 갱신·로그아웃을 직렬화한다.
쿠키 인증 상태를 바꾸는 요청에는 Spring의 `CookieCsrfTokenRepository`와
허용된 출처에서 받은 마스킹 CSRF 토큰을 사용한다.
CORS는 설정한 정확한 출처에만 인증 정보를 포함한 요청을 허용한다.
업무 API는 Bearer 토큰으로 인증한다.

### Redis 인증 상태

Redis는 Lua로 원자적으로 처리하는 요청 횟수 제한과 로그인 묶음 상태를 저장한다.
`JwtAuthenticationFilter`가 서명 검증 후 JWT의 `sid`와 Redis 상태를 비교한다.
로그인 묶음을 폐기하면 이전 회전에서 발급한 토큰을 포함해 해당 로그인의 접근 토큰 전체를 거절한다.

Redis 장애 시 검증을 생략하지 않고 503을 반환한다.
상태가 유실되면 접근을 거절하되, PostgreSQL에 유효한 갱신 토큰이 있으면 복원할 수 있다.
폐기된 토큰으로는 복원할 수 없다.

Redis와 PostgreSQL은 하나의 분산 트랜잭션이 아니다.
실패 시 접근을 허용하지 않는 쪽으로 처리하며, 완료되지 않은 갱신은 DB에서 롤백한다.
명시적인 재사용 거절만 401 응답과 함께 폐기 내용을 커밋한다.
만료 후 7일 지난 갱신 기록은 매시간 삭제한다.

### 추적과 검증

모든 API 응답은 캐시를 금지하고 검증하거나 새로 생성한 `X-Trace-Id`를 포함한다.
인증·워크스페이스 변경은 작업 종류·수행자·대상·추적 번호를 기록하며,
비밀번호·토큰 원문은 남기지 않는다.

CI는 실제 Redis와 H2·PostgreSQL을 사용하는 통합 테스트를 실행한다.
상세 제한값과 운영 설정은 [인증 보안과 운영 설정](AUTH_SECURITY.md)을 참조한다.

## 3단계 기업 커넥터

Spring의 `ConnectorService`가 모든 요청에서 `TenantAccessService`로
멤버십과 역할을 검증한다. 연결 레코드는 `tenant_id`로 범위를 제한한다.
`MockEnterpriseAdapter`는 Alpha/Beta의 API를 공통 `EnterpriseAdapter`
계약으로 변환한다. 외부 호출에는 2초 연결·요청 제한 시간을 적용한다.

연결 설정은 PostgreSQL에 두고 Credential은 `SecretStore`로 암호화해
별도 컬럼에 저장한다. 로컬 구현은 환경 변수의 키와 AES-256-GCM을
사용하며, 운영에서는 같은 인터페이스를 Secrets Manager 구현으로 교체할 수 있다.
Capability는 연결마다 활성 상태를 관리하며 비활성 기능은 서버에서 차단한다.
Mock 시스템은 별도 서비스로 운영하고 Alpha/Beta의 Credential과 데이터가
구분된다. 개발용 Mock 데이터는 프로세스 메모리에 있다.

