# PHASE 3 IMPLEMENTATION REPORT

## 1. 구현 요약

두 기업의 Mock 결제·CRM·메일 시스템을 공통 Spring 커넥터 API로 연결한다.
Alpha와 Beta의 결제 상태를 같은 Payment DTO로 정규화한다.

## 2. 새로 추가된 기능

- Alpha/Beta 고객, 성공·실패 결제, 담당자, CRM 작업, 메일 초안 Mock API
- Credential 검증 후 연결 등록·재검사·해제, 오류 상태와 Capability 관리
- 워크스페이스별 연결 화면과 실패 결제 조회
- 연결 Credential의 AES-256-GCM 암호화

## 3. 수정·추가 파일

`apps/mock-enterprise/src/systems.js`, `apps/platform-api/src/main/java/ai/ontoflow/platform/connector/`,
`apps/platform-api/src/main/resources/db/migration/V3__connectors.sql`,
`apps/web/app/dashboard/[tenant]/connectors/page.tsx`, Compose·환경 설정·테스트·문서.

## 4. API 및 데이터베이스 변경

`docs/API_CONTRACTS.md`의 커넥터 API 참조. `connectors`와
`connector_capabilities` 테이블을 추가한다. `connectors.tenant_id`가
워크스페이스 소유권을 나타내며 조회는 항상 이 값으로 제한한다.

## 5. 사용 기술

Spring Boot, JDBC, Java HttpClient, PostgreSQL, AES-GCM, Node HTTP,
Next.js. 개발용 Mock Credential은 환경 변수로 주입한다.

## 6. 실행 방법

`.env.example`을 복사하여 모든 비밀값을 설정하고
`docker compose up --build`를 실행한다. 계정과 워크스페이스를 만든
후 `/dashboard/{tenant}/connectors`에서 Alpha 또는 Beta를 등록한다.

## 7. 테스트 결과

- `node --test`: Mock API 3개 테스트 통과
- `docker compose --env-file .env.example --profile test run --build --rm platform-api-test`: 통과. 커넥터 격리·암호화·상태 변환 테스트 포함
- `corepack pnpm lint`, `corepack pnpm build`: 통과

## 8. 보안 및 Tenant 격리 영향

모든 커넥터 요청은 실제 로그인 사용자의 멤버십을 재검증한다.
등록·해제·테스트·Capability 변경·외부 쓰기는 관리자 전용이다.
Credential은 별도 암호문으로 저장하고 API 응답에 포함하지 않는다.
개발용 저장소는 `SecretStore` 인터페이스를 통해 교체 가능하다.

## 9. Known Issues

Mock 기업 데이터는 컨테이너 메모리에 있어 재시작 시 생성된 CRM 작업과
메일 초안이 초기화된다. 실제 Salesforce 연동은 Phase 3 선택 사항으로
포함하지 않았다.

## 10. 다음 Phase 준비 상태

커넥터와 Capability 계약을 Phase 4의 MCP Tool Gateway에서 사용할 수 있다.
