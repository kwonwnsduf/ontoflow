# 인증 보안과 운영 설정

## 현재 로그인 흐름

JWT 발급·검증 라이브러리는 JJWT 0.13.0이다.
`JwtTokenProvider`가 토큰을 생성·파싱하고, `JwtAuthenticationFilter`가
Bearer 헤더를 읽어 서명과 Redis 상태를 확인한다.
검증된 사용자·로그인 묶음 정보는 `AuthPrincipal`로 전달한다.

1. 인증 정보를 포함해 `GET /api/v1/auth/csrf`를 호출한다.
   응답으로 받은 마스킹 CSRF 토큰은 메모리에 보관하고, `headerName`에 지정된 헤더로 보낸다.
2. `POST /auth/login` 또는 `POST /auth/register`에 JSON 계정 정보와 CSRF 헤더를 보낸다.
   API는 접근 토큰과 사용자 정보를 응답하고 HttpOnly 갱신 쿠키를 설정한다.
   갱신 토큰은 JSON 응답에 포함하지 않는다.
3. 업무 API에는 Bearer 접근 토큰을 사용한다.
   새로고침 시 보호된 화면을 표시하기 전에 쿠키로 토큰을 갱신한다.
   같은 탭의 요청은 하나의 공통 갱신 작업을 공유하고,
   지원 브라우저의 동일 출처 탭들은 Web Locks로 갱신·로그인·로그아웃을 직렬화한다.
4. `POST /auth/refresh`에는 쿠키와 CSRF 헤더를 보내고,
   JSON 토큰이나 만료된 Authorization 헤더는 보내지 않는다.
   두 토큰을 회전하되 최초 로그인부터 7일인 갱신 만료 시점은 유지한다.
   로그인·회원가입·로그아웃으로 CSRF 쿠키가 초기화되면 CSRF 토큰을 다시 받는다.
5. `POST /auth/logout`에는 쿠키·CSRF 헤더와, 사용 가능한 경우 현재 Bearer 토큰을 보낸다.
   접근 토큰이 만료돼 거절되면 Authorization 없이 다시 요청해 쿠키 세션을 폐기한다.
   유효한 Bearer 토큰이 있으면 갱신 쿠키가 없거나 잘못됐어도 해당 로그인 묶음을 폐기할 수 있다.
   PostgreSQL은 갱신 토큰 묶음을 폐기하고, Redis는 접근 토큰을 차단하며, API는 쿠키를 지운다.
   클라이언트는 서버 성공 응답 후에만 메모리 세션을 지운다.
   네트워크·503 오류는 사용자에게 표시하고 재시도할 수 있게 한다.

## Redis의 역할

### 로그인 묶음 상태

`auth:session:{familyId}`에는 활성 로그인의 사용자 ID 또는 폐기를 뜻하는 `revoked`를 저장한다.
JWT는 `sid=familyId`를 포함한다.
인증 요청마다 Redis의 활성 사용자와 JWT의 사용자 번호가 일치하는지 확인한다.

이 방식은 최신 토큰의 `jti` 하나만 차단하는 대신,
해당 로그인에서 발급된 모든 접근 토큰을 차단한다.

활성 상태는 갱신 토큰의 절대 만료 시점에 JWT 검증 오차 60초를 더한 시점까지 보관한다.
폐기 상태는 7일과 오차 시간 동안 보관한다.

### 인증 요청 횟수 제한

| 요청 종류 | 기본 제한 |
|---|---|
| 로그인 | 정규화한 계정당 15분에 10회 |
| 로그인 | 원격 IP당 15분에 50회 |
| 회원가입 | IP당 1시간에 10회 |
| 토큰 갱신 | IP당 1분에 120회 |

성공한 요청도 횟수에 포함하며 로그인 성공으로 제한을 초기화하지 않는다.
다음 설정으로 기본값을 바꿀 수 있다. 테스트에서는 제한값을 명시적으로 높인다.

- `ontoflow.auth.login-account-limit`
- `ontoflow.auth.login-ip-limit`
- `ontoflow.auth.register-ip-limit`
- `ontoflow.auth.refresh-ip-limit`

횟수 증가와 만료 설정은 하나의 Lua 스크립트로 원자적으로 처리한다.
카운터 키의 계정·IP 식별자는 SHA-256 해시로 저장한다.
제한을 넘으면 429를 반환하고 `Retry-After`에 재시도까지 남은 초를 넣는다.

### 장애와 데이터 유실

Redis 장애 시 인증·갱신·폐기·보호된 API는 503을 반환한다.
Redis의 세션 상태가 없으면 401을 반환한다.

PostgreSQL에 유효한 갱신 토큰이 있으면 Redis의 활성 상태를 복원할 수 있다.
이미 폐기하거나 만료된 토큰으로는 복원할 수 없으므로,
Redis 데이터 유실이 폐기한 로그인을 되살리지는 않는다.

Compose의 Redis는 AOF 영속화와 `noeviction` 정책을 사용한다.
운영에서는 메모리·영속화·지연·가용성을 감시해야 한다.
인증을 적용한 사설 네트워크에 배치하고, 전송 구간 암호화·고가용성·백업 정책을 준비한다.

## 환경별 배포 설정

| 설정 | 로컬 HTTP | 운영 |
|---|---|---|
| `AUTH_COOKIE_SECURE` | 명시적으로 `false` | `true`가 기본값 |
| `WEB_ORIGIN` | `http://localhost:3000` | 정확한 HTTPS 출처. 예: `https://app.example.com` |
| 갱신 쿠키 이름 | `ontoflow_refresh` | `__Host-ontoflow_refresh` |
| 쿠키 속성 | `HttpOnly; SameSite=Strict; Path=/` | 왼쪽 속성과 `Secure` 적용. `Domain` 미설정 |
| `JWT_SECRET` | 로컬용 무작위 비밀값 | 최소 32바이트의 관리형 비밀값. 계획에 따라 교체 |

웹과 API 모두 HTTPS를 사용하고 같은 사이트에 배치한다.
예를 들어 `app.example.com`과 `api.example.com`을 사용할 수 있다.
CORS는 와일드카드나 임의의 출처에 인증 정보를 포함한 요청을 허용하지 않는다.

보안 쿠키 모드에서 `WEB_ORIGIN`이 HTTP이면 시작 단계에서 거절한다.
예제의 비밀값을 운영에 재사용하지 않는다.

### 프록시 뒤에서의 IP 처리

전달 헤더는 기본적으로 신뢰하지 않으며 IP 제한에는 `request.remoteAddr`를 사용한다.
프록시 뒤에 배포할 때는 신뢰할 프록시를 명시적으로 제한한 후 전달 헤더 처리를 설정한다.

- `SERVER_FORWARD_HEADERS_STRATEGY=NATIVE`
- `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`: 신뢰할 프록시 주소를 지정하는 정규식

프록시는 외부에서 들어온 전달 헤더를 덮어써야 하며,
외부에서 API에 직접 접근하는 경로도 제한해야 한다.
설정하지 않으면 프록시 뒤의 모든 사용자가 프록시 IP 하나의 요청 한도를 공유한다.
브라우저가 임의로 보낸 `X-Forwarded-For`를 그대로 신뢰해서는 안 된다.

### 만료·보관·트랜잭션

접근 토큰의 기본 유효기간은 15분, 갱신 토큰의 유효기간은 로그인부터 7일이다.
PostgreSQL에는 갱신 토큰 해시만 저장한다.
만료한 기록은 추가로 7일 보관한 후 매시간 정리한다.
감사 기록에는 별도 보관 정책을 적용한다.

DB와 Redis의 쓰기는 하나의 분산 트랜잭션이 아니다.
실패 시 요청을 거절하며, 접근을 조용히 복원하는 대신 재로그인이 필요할 수 있다.
이미 실행 중인 업무는 이후 로그아웃으로 되돌릴 수 없다.

## 검증 범위

다음 항목을 테스트한다.

- 쿠키만을 사용한 토큰 회전과 쿠키 보안 속성
- CSRF·CORS 검증 실패 시 거절
- 로그아웃·재사용 감지 시 해당 로그인의 모든 접근 토큰 차단
- 동시 갱신 처리와 Redis 상태 유실·장애
- Redis 활성화 실패 시 DB 갱신 롤백
- 공유 요청 횟수 제한
- 프론트엔드 새로고침 후 로그인 복원
- 로그아웃·갱신의 경합 처리

CI는 H2·PostgreSQL 테스트 모두에 Redis를 실행한다.
실제 Redis를 사용한 백엔드 테스트는 DB별 21개,
웹 테스트는 9개가 통과했다. 정적 검사와 운영 빌드도 통과했다.

### 실제 HTTP 검증 실행

기본 요청 제한값을 사용하는 개발 API가 실행 중일 때 다음 명령을 사용한다.

```powershell
node apps/platform-api/scripts/auth-smoke.mjs
```

테스트 계정을 생성한 뒤 실제 HTTP의 쿠키·CSRF 흐름,
로그아웃 차단, 계정 제한의 429·Retry-After 응답을 확인한다.
`PLATFORM_API_URL`과 `WEB_ORIGIN`으로 검증할 대상을 지정할 수 있다.

## 남은 기능과 운영 준비

이번 변경은 기존 비밀번호 로그인을 보완한 것이다.
이메일 인증·이메일 기반 비밀번호 복구·다중 인증(MFA)·기기별 세션 관리 화면·
비밀번호 변경은 별도 구현 대상이다.
HTTPS 배포나 운영 Redis 인프라 구축은 수행하지 않았다.

Web Locks가 없는 브라우저도 인증할 수 있지만,
여러 탭에서 동시에 갱신하면 재사용 감지로 로그인이 종료될 수 있다.

## 참고 자료

- [OWASP 세션 관리 지침](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- [Spring Security CSRF 방어 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Redis 카운터 증가와 만료 처리 문서](https://redis.io/docs/latest/commands/incr/)
- [JJWT 공식 사용 문서](https://github.com/jwtk/jjwt)
