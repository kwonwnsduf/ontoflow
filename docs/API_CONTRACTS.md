# API 명세

## 서비스 상태 확인 API

| 서비스 | 경로 | 응답 |
|---|---|---|
| 웹 | `GET /api/health` | `{ "status": "ok", "service": "web" }` |
| 플랫폼 API | `GET /actuator/health` | Spring Actuator의 상태 확인 응답 |
| 플랫폼 API | `GET /api/v1` | 1단계 플랫폼 API의 준비 상태 |
| AI 서비스 | `GET /health` | `{ "status": "ok", "service": "ai-service" }` |
| 모의 기업 시스템 | `GET /health` | `{ "status": "ok", "service": "mock-enterprise" }` |

Spring OpenAPI 문서는 `/v3/api-docs`, Swagger UI는 `/docs`에서 제공한다.
FastAPI OpenAPI 문서는 `/openapi.json`, Swagger UI는 `/docs`에서 제공한다.

모든 애플리케이션 HTTP 응답에는 요청 추적 번호인 `X-Trace-Id`가 포함된다.
요청에 들어온 값이 `[A-Za-z0-9._-]{1,64}`에 맞으면 사용하고, 그렇지 않으면 새로 생성한다.

## 2단계 인증·워크스페이스 API

아래 경로의 공통 접두사는 `/api/v1`이다.
인증이 필요한 API에는 `Authorization: Bearer <accessToken>` 헤더를 보낸다.
요청·응답 본문은 JSON을 사용한다.

| 메서드 | 경로 | 요청과 처리 결과 |
|---|---|---|
| GET | `/auth/csrf` | `{headerName,token}` 응답과 HttpOnly CSRF 쿠키 발급 |
| POST | `/auth/register` | `{email,password,displayName}` → 201, 로그인 정보 반환 |
| POST | `/auth/login` | `{email,password}` → 로그인 정보 반환 |
| POST | `/auth/refresh` | 갱신 쿠키와 CSRF 헤더 → 토큰 재발급. JSON 토큰은 받지 않음 |
| POST | `/auth/logout` | 갱신 쿠키와 CSRF 헤더 → 204, 로그인 묶음 폐기 및 쿠키 삭제 |
| GET | `/auth/me` | 현재 사용자 `{id,email,displayName}` |
| GET | `/tenants` | 내가 소속된 워크스페이스만 페이지 단위로 반환 |
| POST | `/tenants` | `{name,slug}` → 201, 워크스페이스 생성 및 생성자를 OWNER로 등록 |
| GET | `/tenants/{id}` | 접근 권한을 확인한 워크스페이스 `{id,name,slug,role}` |
| POST | `/tenants/{id}/switch` | 멤버십 확인 후 워크스페이스 전환 기록 저장 |
| GET | `/tenants/{id}/members` | OWNER·ADMIN 전용 멤버 목록 조회 |
| POST | `/tenants/{id}/members` | 가입된 사용자의 `{email,role}` → 201, 멤버 추가 |
| PATCH | `/tenants/{id}/members/{memberId}` | `{role}` → 204, 역할 변경 |
| DELETE | `/tenants/{id}/members/{memberId}` | 멤버십 제거 → 204 |
| GET | `/tenants/{id}/audit-logs` | OWNER·ADMIN 전용 워크스페이스 감사 기록 조회 |

### 로그인 응답과 쿠키

로그인 응답 형식은 다음과 같다.

```json
{
  "accessToken": "...",
  "expiresIn": 900,
  "user": {
    "id": "...",
    "email": "...",
    "displayName": "..."
  }
}
```

갱신 토큰은 JSON에 포함하지 않는다. 회원가입·로그인·갱신 시 HttpOnly,
SameSite=Strict 속성이 있는 호스트 전용 쿠키로 전달한다.

- 운영: `__Host-ontoflow_refresh; Secure; Path=/`
- 명시적으로 설정한 로컬 HTTP: `ontoflow_refresh`, Secure 속성 제외

브라우저 요청에는 `credentials: "include"`를 사용한다.
인증 상태를 바꾸는 요청 전에는 `/auth/csrf`에서 마스킹된 CSRF 토큰을 받고,
응답의 `headerName`인 `X-XSRF-TOKEN` 헤더에 넣어 보낸다.
로그인·회원가입·로그아웃은 CSRF 쿠키를 초기화하므로 이후 토큰을 다시 받아야 한다.

JSON 본문으로 갱신 토큰을 제출하는 방식은 지원하지 않는다.
업무 API는 계속 Bearer 인증을 사용하며 갱신 쿠키만으로 사용자를 인증하지 않는다.

### 입력과 권한 규칙

회원가입 비밀번호는 출력 가능한 ASCII 문자 12~72자로 제한하고,
BCrypt 작업 강도 12로 해싱한다. 이메일은 소문자로 정규화한다.
`slug`에는 영문 소문자·숫자와 단어 사이의 단일 하이픈을 사용할 수 있다.

역할은 `OWNER`, `ADMIN`, `OPERATOR`, `VIEWER`이다.
OWNER만 OWNER·ADMIN 역할을 부여하거나 변경할 수 있다.
마지막 OWNER는 삭제하거나 강등할 수 없다.
모든 워크스페이스 요청에서 멤버십을 확인하며, 클라이언트가 선택한 테넌트 값만으로 권한을 부여하지 않는다.

### 페이지네이션과 오류

페이지네이션 기본값은 `page=0&size=20`이다.
`size`는 1~100, `page`는 0~100000을 허용한다.
응답은 `{items,page,size,total}`, 오류는 `{status,message,traceId}` 형식이다.

| 상태 코드 | 의미 |
|---|---|
| 400 | 입력 검증 실패 |
| 401 | 인증 실패 또는 갱신 거절 |
| 403 | 워크스페이스·역할 권한 부족 또는 CSRF 검증 실패 |
| 404 | 멤버나 추가할 가입 사용자를 찾지 못함 |
| 409 | 중복 또는 상태 충돌 |
| 429 | 인증 요청 횟수 초과. `Retry-After`에 재시도까지 남은 초 반환 |
| 503 | 인증 상태 저장소 이용 불가 |

모든 API 응답에는 `X-Trace-Id`와 `Cache-Control: no-store`가 포함된다.
멤버·감사 기록 응답에는 토큰·해시·비밀값을 포함하지 않는다.

## 3단계 커넥터 API

모든 경로의 접두사는 `/api/v1/tenants/{tenant}/connectors`이다.
`tenant`는 JWT의 사용자 멤버십으로 매 요청 검증한다. 연결 ID를 다른
워크스페이스 경로에 넣으면 404를 반환한다. Credential은 등록 요청에만
포함하고 응답·로그에는 포함하지 않는다.

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/` | 워크스페이스 연결 목록과 Capability 조회 |
| POST | `/` | `{systemType: ALPHA\|BETA, name, credential}` 등록·연결 테스트. OWNER·ADMIN |
| GET | `/{id}` | 상태, 마지막 오류와 Capability 조회 |
| POST | `/{id}/test` | 연결 재검사. OWNER·ADMIN |
| PATCH | `/{id}/capabilities/{action}` | `{enabled}` 변경. OWNER·ADMIN |
| DELETE | `/{id}` | 연결 해제. OWNER·ADMIN |
| GET | `/{id}/payments/failed` | 공통 Payment DTO 목록 |
| GET | `/{id}/payments/{paymentId}` | 공통 Payment DTO |
| GET | `/{id}/customers/{customerId}` | 공통 Customer DTO |
| GET | `/{id}/employees` | 공통 Employee DTO |
| POST | `/{id}/followups` | `{customerId,assigneeId,summary}` 생성. OWNER·ADMIN |
| POST | `/{id}/drafts` | `{to,subject,content}` 생성. OWNER·ADMIN |

Capability: `payment.list_failed`, `payment.get_detail`,
`crm.find_customer`, `crm.list_employees`, `crm.create_followup`,
`email.create_draft`. Beta의 `DECLINED`는 공통 `FAILED`,
`SETTLED`는 `PAID`로 변환한다. 외부 Credential 오류와 기타
기업 API 오류는 502, Timeout은 504를 반환한다.

### 토큰 만료와 폐기

접근 토큰의 기본 유효기간은 15분이며 검증 시 60초의 시계 오차를 허용한다.
JWT의 `sid`는 로그인 묶음 번호인 `familyId`를 나타내며, 인증 요청마다 Redis에서 상태를 확인한다.

갱신 토큰은 로그인 시점부터 7일 후 만료된다. 회전해도 만료 시점은 연장되지 않는다.
로그아웃·재사용 감지 시 PostgreSQL의 해당 로그인 묶음을 폐기하고,
Redis에서 그 묶음의 모든 접근 토큰을 차단한다.

Redis 세션 상태가 없으면 접근을 거절한다.
Redis 데이터 유실 후에도 유효한 갱신 쿠키가 있으면 상태를 복원할 수 있지만,
폐기·만료된 토큰으로는 복원할 수 없다. Redis 장애 시에는 인증 검증을 생략하지 않고 503을 반환한다.
로그아웃 당시 이미 실행 중이던 업무 요청까지 되돌리지는 않는다.

