# [DATAIZE AI 과제] AWS Marketplace Billing Mock 연동 — 최종 구현 계획 (v3)

- 작성: 2026-09-17 / 마감: **2026-09-18 18:00**
- 근거: 과제 메일, 교보DTS 온보딩 가이드 PDF(p6~17 지정, p19~23 QuickStart 참고), AWS SDK v3 `client-marketplace-metering`/`client-marketplace-entitlement-service` 3.1134.0 타입 문서
- 검토: v1 → Codex 리뷰(조건부 승인) → v2 반영(§13) → **v3: 결정사항 반영(스택 Vue 3 + Spring Boot, 작업 위치 `C:\dev`, 레포 public)**
- 원격 레포: https://github.com/fdrn9999/aws-marketplace-billing-mock (public)

표기: **[가이드]** 제공 문서 근거 / **[AWS]** SDK 문서로 확인한 실제 동작 / **[Mock 정책]** 과제용으로 단순화하거나 가정한 규칙 (README "가정과 단순화" 절에 그대로 옮김)

---

## 1. 목표와 범위
과제 흐름 `구독 확인 → 권한 확인 → 사용량 발생 → Metering 처리 → UI 반영`을 **가이드의 API 3종(ResolveCustomer / GetEntitlements / BatchMeterUsage)과 온보딩(Fulfillment URL)** 위에서 끝까지 동작시킨다.
평가 초점이 "문서 이해, 데이터/API 설계, FE-BE 연결"이므로 디자인과 부가 기능은 뒤로 미룬다.

## 2. 기술 스택
| 영역 | 선택 |
|---|---|
| Backend | **Spring Boot 4.1.1**, Java 17, Gradle 9.7(wrapper), spring-boot-starter-webmvc + validation, RestClient(Mock AWS 호출) |
| Frontend | **Vue 3.5 + Vite 8 + TypeScript**, Vue Router, fetch 기반 API 모듈 + composable(로딩/에러 상태) |
| 저장소 | In-memory repository(`ConcurrentHashMap`) + `backend/src/main/resources/mock-data/*.json` 시드 (재시작하거나 reset하면 초기화) |
| 테스트 | JUnit 5 + Spring Boot Test(`RANDOM_PORT`, 실제 HTTP). Playwright는 happy-path 1개와 스크린샷 생성용 |
| 실행 | 루트에서 `npm run setup && npm run dev` (`concurrently`로 `gradlew bootRun`과 `vite`를 동시 실행. `scripts/gradlew.mjs`가 Windows와 macOS/Linux 차이를 흡수) |

- FE/BE 타입은 공유하지 않는다. BE는 Java record DTO, FE는 `frontend/src/api/types.ts`에 같은 계약을 수동으로 정의한다.
- 포트: BE 8080, FE 5173. Vite 프록시로 `/api`, `/mock-aws`, `/marketplace/fulfillment`를 BE로 넘긴다(CORS 불필요). Mock Marketplace 화면 경로는 프록시와 겹치지 않도록 `/aws-marketplace`로 둔다.
- AWS SDK 어댑터는 구현하지 않는다. `MarketplaceClient` 인터페이스만 두고, 실제 SDK(AWS SDK for Java v2 `marketplacemetering`, `marketplaceentitlement`)로 교체하는 방법은 README에 설명한다(Codex 권고 수용).

## 3. 아키텍처와 흐름
```
[Mock Marketplace 화면] ─(구독 클릭)→ POST /mock-aws/_sim/purchase : 토큰·구독·Entitlement 생성
      │ 브라우저 form POST  x-amzn-marketplace-token=<token>                          [가이드][AWS]
      ▼
[POST /marketplace/fulfillment] ── 즉시 ResolveCustomer 호출(토큰 즉시 redeem)          [AWS]
      │  성공 → OnboardingSession 생성 → 302 /register?onboarding=<id>   (토큰은 URL에 노출하지 않음)
      │  실패 → 302 /register?error=EXPIRED_REGISTRATION_TOKEN | INVALID_REGISTRATION_TOKEN
      ▼
[등록 화면] AWS 계정/제품 표시 + 회사·담당자 입력 → POST /api/subscribers {onboardingId,...}
      │  Subscriber upsert(key=LicenseArn) → GetEntitlements 동기화 (계약/혼합형)       ① 구독 정보 확인
      ▼
[Dashboard] GET /api/me/subscription → deriveStatus()                                  ② 권한 확인
      │  POST /api/features/analysis/run  (requireEntitlement)                          ③ 사용량 발생
      │     → UsageEvent + 시간 버킷 MeteringRecord(PENDING) 누적 (구독 단위 락 안에서 원자적으로 처리)
      │  [+1시간] [미터링 실행] → Metering Job → BatchMeterUsage(≤25건/요청)            ④ Metering
      ▼
[Dashboard] 사용량·포함량·초과량, 예상 청구액, 미터링 레코드 상태, AWS 호출 로그        ⑤ UI 반영
```
- 토큰 처리 위치: QuickStart(p21)는 등록 폼 제출 시점에 resolve한다. 반면 SDK 문서는 토큰을 오래 보유하거나 재제출하면 `ExpiredTokenException`이 난다며 **제출 즉시 redeem**을 권고한다. 그래서 fulfillment 수신 즉시 resolve하는 방식을 택하고, 그 이유를 README에 적는다.
- Mock AWS(`/mock-aws/*`)는 같은 Spring Boot 앱 안의 별도 컨트롤러 묶음이다. 앱은 `HttpMarketplaceClient`(RestClient)로 **HTTP 호출**하므로 네트워크 오류와 재시도 경로가 실제와 같은 모양이 된다.

## 4. 데이터 설계
### 4.1 식별 키 — Concurrent Agreements 반영 [AWS]
2026-06-01부터 신규 SaaS 연동은 `CustomerIdentifier` 대신 **`CustomerAWSAccountId`**, `ProductCode` 대신 **`LicenseArn`**을 사용해야 한다. 한 AWS 계정이 여러 계약을 동시에 가질 수 있기 때문이다.
→ 앱 내부 PK는 `subscriberId`(내부 ID)로 두고, **유니크 키는 `licenseArn`**으로 한다. `customerIdentifier`는 레거시 호환용 선택 필드로 보존한다.
→ 가이드 p13/p16/p17 예시는 두 방식이 섞인 과도기 형태다. Mock은 가이드 형태를 그대로 받아주고, 앱 클라이언트는 최신 규칙으로 보낸다. 이 차이를 README 표로 정리한다.

### 4.2 Mock AWS 측 데이터 (`mock-data/aws/`)
| 파일 | 필드 |
|---|---|
| `registration-tokens.json` | token, customerAWSAccountId, customerIdentifier?, productCode, licenseArn, expiresAt, **redeemed**(1회용) |
| `licenses.json` | licenseArn, customerAWSAccountId, productCode, state(`ACTIVE`/`CANCELLED`), isFreeTrial |
| `entitlements.json` | 가이드 p17 형태: CustomerIdentifier?, CustomerAWSAccountId, ProductCode, LicenseArn, Dimension, ExpirationDate, Value{IntegerValue} |

### 4.3 앱(판매자) 측 데이터 (`mock-data/app/`)
| 엔티티 | 필드 | 근거 |
|---|---|---|
| Product | productCode, name, pricingModel(`SUBSCRIPTION`/`CONTRACT`/`CONTRACT_WITH_SUBSCRIPTION`), dimensions[{key, unit, unitPrice?}] | [가이드] p8/p15 |
| Subscriber | subscriberId, licenseArn(UK), customerAWSAccountId, customerIdentifier?, productCode, companyName, contactPerson, contactPhone, contactEmail, successfullyRegistered, successfullySubscribed, subscriptionExpired, expiredReason?, isFreeTrialTermPresent, entitlements[], termStartAt, lastEntitlementSyncAt, createdAt, updatedAt | [가이드] p21~22 필드 의미 참고(camelCase로 바꾸고 확장) |
| OnboardingSession | id, licenseArn, customerAWSAccountId, productCode, expiresAt, consumed | [Mock 정책] |
| UsageEvent | id, subscriberId, dimension, quantity, includedQuantity, overageQuantity, idempotencyKey?, occurredAt | [Mock 정책] |
| MeteringRecord | id, **UK(licenseArn, dimension, hourStart)**, customerAWSAccountId, quantity, status(`PENDING`/`SENDING`/`SUCCESS`/`DUPLICATE`/`FAILED`), meteringRecordId?, attempts, lastError?, sentAt? | [가이드] p23 테이블을 확장(삭제하지 않고 이력 보존) |

### 4.4 시드 시나리오
| 고객 | 모델 | 초기 상태 | 보여줄 것 |
|---|---|---|---|
| usage-active | SUBSCRIPTION | ACTIVE | 전량 미터링, PENDING과 SUCCESS 혼재 |
| contract-active | CONTRACT (100회) | ACTIVE, 95회 사용 | 한도 임박 → 초과 시 403 `QUOTA_EXCEEDED` |
| hybrid-overage | HYBRID (포함 50회) | ACTIVE, 초과 중 | 포함/초과 분할, 초과분만 미터링 |
| contract-expired | CONTRACT | EXPIRED | 만료 → 403 `SUBSCRIPTION_EXPIRED`, 조회만 허용 |
| pending | SUBSCRIPTION | NOT_SUBSCRIBED | 등록만 됨(`SUBSCRIPTION_PENDING`) |
| + 구매 시뮬레이터 | 3개 모델 | 신규 | Fulfillment → 등록 전체 흐름 |
| + 토큰 시나리오 | - | - | invalid / expired / 재제출 토큰 |

## 5. 구독 상태와 접근 권한
### 5.1 상태 계산 — `deriveStatus(subscriber, product, now)` (순수 함수, 테이블 기반 테스트)
위에서부터 먼저 걸리는 규칙을 적용한다.
1. 구독자 없음 → `NOT_SUBSCRIBED` / `NOT_REGISTERED`
2. `subscriptionExpired=true` → `EXPIRED` / `expiredReason`(`UNSUBSCRIBED` | `METERING_REJECTED`)
3. `successfullySubscribed=false` → `NOT_SUBSCRIBED` / `SUBSCRIPTION_PENDING`
4. CONTRACT·HYBRID이고 동기화 이력 없음 → `NOT_SUBSCRIBED` / `ENTITLEMENT_UNVERIFIED` (fail-closed)
5. CONTRACT·HYBRID이고 유효(`ExpirationDate > now`) Entitlement가 0개 → `EXPIRED` / `CONTRACT_EXPIRED`
6. 그 외 → `ACTIVE` (부가: `isFreeTrial`, `expiresAt`, 7일 이내 만료 경고, `stale`)
- 차원 일부만 만료된 경우: 상태는 ACTIVE이고 **권한 검사는 차원 단위**로 한다(해당 차원 Entitlement가 만료되면 그 기능만 403).
- SUBSCRIPTION 모델은 조회 API가 없고 이벤트가 상태의 원천이다 [AWS]. 추가로 BatchMeterUsage가 `CustomerNotSubscribed`를 돌려주면 `EXPIRED/METERING_REJECTED`로 전환해서 로컬 상태와 AWS 상태를 맞춘다.

### 5.2 접근 정책 (`requireEntitlement(dimension)`)
| 상태·모델 | 기능 실행 | 미터링 | 응답 |
|---|---|---|---|
| ACTIVE · SUBSCRIPTION | 허용 | 전량 | 200 |
| ACTIVE · CONTRACT | 계약 수량까지 | 없음 | 초과 시 403 `QUOTA_EXCEEDED` |
| ACTIVE · HYBRID | 허용 | 초과분만 | 200 (`overage` 표시) |
| EXPIRED | 차단 (조회는 허용) | 없음 | 403 `SUBSCRIPTION_EXPIRED` + reason |
| NOT_SUBSCRIBED | 차단 | 없음 | 403 `NOT_SUBSCRIBED` + reason |
| 고객 식별 불가 | 차단 | - | 401 `UNAUTHENTICATED` |

- **[Mock 정책] CONTRACT 수량 의미**: `Value.IntegerValue`를 **계약 기간(termStartAt ~ ExpirationDate) 동안의 총 실행 가능 횟수**로 해석한다. `entitlement-updated`(갱신)가 오면 새 기간이 시작되고 사용량이 리셋된다. 실제 AWS 계약 차원은 좌석이나 티어처럼 상품마다 의미가 다르다는 점을 README에 적는다.
- **[Mock 정책] HYBRID 분할**: `remaining = max(0, included − usedInTerm)`, `includedQty = min(qty, remaining)`, `overageQty = qty − includedQty`. 초과분만 MeteringRecord에 누적한다.
- **원자성**: "잔여량 확인 → UsageEvent 기록 → 버킷 누적"은 구독(licenseArn)별 락 안에서 한 번에 처리한다(Tomcat 요청 스레드가 동시에 들어와도 한도를 넘지 않음). 버킷 누적은 미터링 잡과도 같은 저장소 락을 쓴다. DB로 옮기면 조건부 쓰기나 트랜잭션이 필요하다고 README에 적는다.
- **Entitlement 캐시 정책**: 권한 검사 시 캐시 나이가 15분(시뮬레이션 시계 기준)을 넘으면 GetEntitlements로 재동기화를 시도한다. 실패하면 **마지막 동기화 후 24시간 이내이고 만료 전인 경우에만** 허용하고 `stale: true`를 표시한다. 그 외에는 503 `ENTITLEMENT_UNAVAILABLE`로 거부한다(fail-closed).
- **데모 인증**: `X-Customer-Id` 헤더(UI 고객 스위처)로 대신한다. 누구나 사칭할 수 있으므로 **데모 전용**이라고 UI 배지와 README에 명시한다.
- **데모 전용 API 보호**: `/api/admin/*`, `/mock-aws/_sim/*`은 `DEMO_MODE=true`(기본값)일 때만 마운트한다. 운영에서는 비활성화 대상으로 명시한다.

## 6. API 계약
### 6.1 Mock AWS API — 요청/응답은 Java record DTO(PascalCase JSON)와 fixture로 고정하고 계약 테스트를 작성한다
| API | Request | Response | 예외 (`{__type, message}` + 4xx/5xx) |
|---|---|---|---|
| POST `/mock-aws/metering/resolve-customer` | `{RegistrationToken}` | `{CustomerAWSAccountId, CustomerIdentifier?, ProductCode, LicenseArn}` | `InvalidTokenException`, `ExpiredTokenException`(만료 **또는 재제출**) [AWS], `ThrottlingException` |
| POST `/mock-aws/entitlement/get-entitlements` | `{ProductCode, Filter, NextToken?, MaxResults?}` — Filter 키는 SDK의 `LICENSE_ARN`/`CUSTOMER_AWS_ACCOUNT_ID`와 가이드의 `LicenseArn` 표기를 **모두 허용** | `{Entitlements[], NextToken?}` (가이드 p17 필드) | `InvalidParameterException`, `ThrottlingException`, `InternalServiceErrorException` |
| POST `/mock-aws/metering/batch-meter-usage` | `{ProductCode?, UsageRecords[{CustomerAWSAccountId, LicenseArn, Dimension, Quantity, Timestamp}]}` | `{Results[{MeteringRecordId, Status, UsageRecord}], UnprocessedRecords[]}` (가이드 p16) | `TimestampOutOfBoundsException`(**24시간 이상 지난 레코드**, 요청 단위) [AWS], `InvalidLicenseException`, `InvalidUsageDimensionException`, `ThrottlingException`, `InternalServiceErrorException`. 26건 이상이면 400 [Mock 정책: 예외명 단순화] |

- 레코드 단위 Status는 `Success` / `CustomerNotSubscribed` / `DuplicateRecord` [AWS].
- [Mock 정책] Duplicate 판정 키는 `(LicenseArn, Dimension, 시간 단위로 내림한 Timestamp)`다. **완전히 같은 요청을 재전송하면 같은 결과를 돌려준다**(멱등) [AWS].
- [Mock 정책] `UnprocessedRecords`는 장애 주입 설정이 켜졌을 때만 생긴다(실제 의미인 "서비스 측 오류로 재시도 대상"을 재현) [AWS].
- 시뮬레이터(데모 전용): `POST /_sim/purchase`, `POST /_sim/events`, `GET /_sim/calls`(호출 로그), `POST /_sim/faults`(P1).
- [Mock 정책] 이벤트: 실제로는 EventBridge의 `detail-type`과 SNS의 action 문자열이 서로 다르다. 여기서는 **mock 이벤트 계약** `{type: 'SUBSCRIPTION_STARTED' | 'ENTITLEMENT_UPDATED' | 'SUBSCRIPTION_CANCELLED', licenseArn}` 하나로 통일하고, 실제 이벤트와의 대응표를 README에 둔다.

### 6.2 앱 API — 공통 에러 형식 `{ error: { code, message, details?, requestId } }`
| Method / Path | 설명 | 우선순위 |
|---|---|---|
| POST `/marketplace/fulfillment` | form `x-amzn-marketplace-token` → 즉시 resolve → 302 | P0 |
| GET `/api/onboarding/:id` | 등록 화면용 resolve 결과(계정/제품) | P0 |
| POST `/api/subscribers` | `{onboardingId, companyName, contactPerson, contactPhone, contactEmail}` → upsert(재등록은 멱등 처리, `alreadyRegistered`) → Entitlement 동기화 | P0 |
| GET `/api/customers` | 데모 고객 스위처 목록 | P0 |
| GET `/api/me/subscription` | status, reason, pricingModel, entitlements, expiresAt, stale | P0 |
| POST `/api/features/analysis/run` | 보호 기능 → 사용량 기록 (`Idempotency-Key` 지원은 P1) | P0 |
| GET `/api/me/usage` | 현재 기간의 차원별 used/included/overage | P0 |
| GET `/api/me/billing` | 미터링 레코드, 상태별 집계, **예상** 청구액 | P0 |
| POST `/api/admin/metering/run` | 미터링 잡 수동 실행 → `{sent, success, duplicate, failed, unprocessed}` | P0 |
| POST `/api/admin/clock` | `{advance: '1h'|'1d'|'31d'}` / reset (역행 금지 → 400) | P0 |
| POST `/api/admin/reset` | 시드로 초기화 | P0 |
| POST `/api/internal/marketplace-events` | mock 이벤트 수신 → 재동기화 또는 해지 처리 | P0(구독 시작) / P1(갱신·해지) |
| GET `/api/health` | 헬스체크 | P0 |

에러 코드: `UNAUTHENTICATED` 401 · `NOT_SUBSCRIBED` / `SUBSCRIPTION_EXPIRED` / `QUOTA_EXCEEDED` 403 · `INVALID_REGISTRATION_TOKEN` / `EXPIRED_REGISTRATION_TOKEN` / `ONBOARDING_SESSION_EXPIRED` / `UNKNOWN_PRODUCT` / `VALIDATION_ERROR` / `INVALID_CLOCK_OPERATION` 400 · `NOT_FOUND` 404 · `METERING_ALREADY_RUNNING` / `IDEMPOTENCY_KEY_CONFLICT` 409 · `MARKETPLACE_UNAVAILABLE` / `ENTITLEMENT_UNAVAILABLE` 503(+`Retry-After`) · `INTERNAL_ERROR` 500

## 7. Metering / Billing
1. **대상 선정**: 시뮬레이션 시계 기준으로 `hourStart + 1h <= now`인 **마감된 버킷** 중 `PENDING` 상태인 것. 현재 시간 버킷은 보내지 않는다.
2. **0 사용량 전송** [가이드 p15] + [Mock 정책 세부]: ACTIVE인 **SUBSCRIPTION(사용량 기반)** 구독에서 마감 시간에 어떤 차원의 레코드도 없으면, 대표 차원(첫 번째 차원)으로 quantity 0 레코드를 만든다. 가이드가 "사용량 없더라도 0 전송"을 사용량 기반 모델에만 적었으므로 혼합형에는 적용하지 않는다. 최근 3시간 안에서 (구독 시작 이후) 레코드가 없는 시간대를 찾아 보충한다(시계를 +31일 옮겨도 폭증하지 않도록). ※ 구현 중 v2의 "SUBSCRIPTION/HYBRID + watermark"에서 변경
3. **만료 레코드 사전 차단** [AWS]: 24시간 이상 지난 버킷은 보내지 않고 `FAILED(TIMESTAMP_OUT_OF_BOUNDS)`로 처리한다. 요청 단위 예외 때문에 배치 전체가 실패하는 것을 막는다.
4. **Claim**: 대상 레코드를 `SENDING`으로 바꾼 뒤 `productCode`별로 묶고, 25건씩 나눠 BatchMeterUsage를 호출한다. 앱 클라이언트는 LicenseArn 방식이므로 `ProductCode`를 생략한다 [AWS].
5. **결과 처리**
   - `Success` → `SUCCESS` + `meteringRecordId`. **다시 보내지 않는다**
   - `DuplicateRecord` → `DUPLICATE` (이미 반영된 것으로 보고 종료)
   - `CustomerNotSubscribed` → `FAILED` + 해당 구독을 `EXPIRED/METERING_REJECTED`로 전환
   - `UnprocessedRecords` → `PENDING`으로 되돌리고 `attempts+1`
   - 호출 자체가 실패(Throttling/5xx)하면 지수 백오프로 최대 3회 재시도한다. 그래도 실패하면 해당 청크만 `PENDING`으로 되돌리고 `attempts+1`
   - [Mock 정책] `attempts >= 5`면 `FAILED(MAX_ATTEMPTS)`
6. **동시성**: `ReentrantLock.tryLock()`으로 막고 `finally`에서 해제한다. 실행 중에 다시 요청하면 409 `METERING_ALREADY_RUNNING`.
7. **자동 실행**: P0는 수동 실행(Dashboard 버튼)이다. `setInterval` 자동 실행은 P1.
8. **Billing 요약(추정)**: 차원별 `SUCCESS 수량 × 단가`, 계약 고정요금 표시. "실제 청구는 AWS가 수행"이라고 명시한다.

## 8. Frontend
| 화면 | 내용 | 우선순위 |
|---|---|---|
| `/` Dashboard | 고객 스위처(데모 배지), 상태 배지 + 사유, 만료 임박 경고, 보호 기능 실행(권한 없으면 잠금 + 403 사유), 사용량 카드(포함/사용/초과 진행바), 예상 청구, 미터링 레코드 테이블, **데모 컨트롤**(+1h / +1d / +31d, 미터링 실행, 리셋) | P0 |
| `/aws-marketplace` | 모델별 상품 카드 → 구독(form POST), invalid/expired 토큰 시나리오 버튼 | P0 |
| `/register` | resolve된 AWS 계정/제품 표시, 폼 검증, 토큰 오류 안내 화면 | P0 |
| AWS 호출 로그 패널 | ResolveCustomer/GetEntitlements/BatchMeterUsage 요청·응답 타임라인 | P1 |
| 이벤트 발행·장애 주입 패널 | 해지·갱신 이벤트, throttle/unprocessed 토글 | P1 |
- 공통: 로딩, 빈 상태, 에러 배너(코드별 문구), 재시도 버튼. 스타일은 최소한의 CSS만 쓴다.

## 9. 디렉터리 구조
```
aws-marketplace-billing-mock/
├─ package.json                         # setup/dev/test/build/e2e 스크립트 (concurrently)
├─ scripts/gradlew.mjs                  # OS별 gradlew 실행 래퍼
├─ backend/                             # Spring Boot 4.1 (Gradle)
│  └─ src/main/java/io/github/fdrn9999/marketplace/
│     ├─ common/        # ErrorCode, ApiException, GlobalExceptionHandler, SimulatedClock
│     ├─ config/        # AppProperties, RestClient 설정
│     ├─ domain/        # Product, Subscriber, UsageEvent, MeteringRecord, enum들
│     ├─ store/         # In-memory repositories + SeedDataLoader
│     ├─ mockaws/       # Mock AWS 3개 API, 시뮬레이터, 호출 로그 (AWS JSON 형태 DTO)
│     ├─ client/        # MarketplaceClient 인터페이스, HttpMarketplaceClient(RestClient), 재시도
│     ├─ onboarding/    # Fulfillment URL, 등록
│     ├─ subscription/  # 상태 계산, Entitlement 동기화, 이벤트 처리
│     ├─ access/        # 고객 식별(X-Customer-Id), EntitlementGuard
│     ├─ usage/         # 보호 기능, 사용량 기록
│     ├─ metering/      # MeteringJob
│     ├─ billing/       # 사용량·청구 요약
│     └─ admin/         # 시계, 리셋, 미터링 실행, 고객 목록
│  └─ src/main/resources/mock-data/{aws,app}/*.json
├─ frontend/                            # Vue 3 + Vite + TS
│  └─ src/{api,composables,components,views,router}
├─ e2e/                                 # Playwright happy-path + 스크린샷
├─ docs/                                # PLAN.md, screenshots/
└─ README.md
```

## 10. 테스트와 검증
- **Unit (P0)**: `deriveStatus` 규칙표, 접근 매트릭스, CONTRACT 한도 경계(99→100→101), HYBRID 분할 경계, 버킷 집계와 마감 판정, 25건 분할, 결과 처리(Success/Duplicate/NotSubscribed/Unprocessed), 24시간 사전 차단, Mock 3개 API 계약(fixture)
- **Integration (P0)**: 구매 → fulfillment 302 → 등록 → ACTIVE → 기능 실행 → +1h → 미터링 → SUCCESS → billing 반영. 에러 경로(invalid/expired/재제출 토큰, EXPIRED 403, QUOTA 403, 미터링 중복 실행 409)
- **E2E (P0, 1개)**: Playwright로 위 흐름을 UI에서 수행하고 스크린샷 4~6장을 `docs/screenshots/`에 저장한다. 실제 브라우저 렌더링 확인도 겸한다.
- **완료 게이트**: `npm test` 통과 · `npm run build` 성공 · E2E 통과 · **새로 클론한 폴더에서 README 절차대로 실행 확인** · push 확인

## 11. 일정 (실작업 약 9h + 버퍼)
| # | 작업 | 시간 |
|---|---|---|
| 1 | 레포 스캐폴드(Spring Initializr + create-vite), dev 스크립트 Windows 검증, 원격 push | 0.5h |
| 2 | 도메인 모델, 시드 데이터, store, clock, 에러 모델 | 1h |
| 3 | Mock AWS 3개 API + 계약 테스트 | 1.5h |
| 4 | 상태/접근/사용량 + 단위 테스트 → Dashboard 연동 가능한 API | 1.5h |
| 5 | Metering Job + Billing + 테스트 | 1h |
| 6 | Frontend Dashboard(데모 컨트롤 포함) | 1.5h |
| 7 | 온보딩(구매 시뮬레이터 → fulfillment → 등록) BE+FE + 통합 테스트 | 1h |
| 8 | Playwright happy-path + 스크린샷 | 0.5h |
| 9 | **README 작성**(독립 배정) | 1h |
| 10 | 클린 클론 검증, 수정, 최종 push, 공개 전환 | 0.5h |
| - | 버퍼 / P1 | 남는 시간 |

- **P1** (여유가 있을 때): AWS 호출 로그 패널, 이벤트(갱신/해지) 패널, 장애 주입, Idempotency-Key, 자동 미터링 interval, GitHub Actions CI
- **P2**: 실행 영상(GIF/webm), Docker

## 12. README 구성 (평가자 기준)
1. 한 줄 요약과 스크린샷 · 2. **3분 데모 시나리오**(순서대로 클릭) · 3. 실행 방법(Node 버전, 포트, 명령 한 줄) · 4. 아키텍처와 시퀀스 다이어그램(mermaid) · 5. **과금 모델 ↔ API 매핑표** · 6. 데이터 모델 · 7. 상태/권한 매트릭스 · 8. API 목록과 에러 코드 · 9. Metering 처리 규칙 · 10. **가이드 vs 최신 AWS(Concurrent Agreements) 차이** · 11. 가정과 단순화([Mock 정책] 목록) · 12. 보안과 한계(데모 인증, in-memory, admin API) · 13. 실제 AWS로 전환하는 방법(SDK 교체 지점, EventBridge/SQS, DynamoDB) · 14. 테스트 방법
- 가이드 PDF는 "Amazon Confidential" 표기가 있으므로 **커밋하지 않고** 링크만 남긴다. `.env.example`만 커밋한다.

## 13. Codex 리뷰 반영 내역
| Codex 지적 | 처리 | 근거 |
|---|---|---|
| Mock 3개 API의 req/res 스키마·fixture 명시 | 수용 §6.1 | - |
| "6시간 초과 → Unprocessed"는 사실이 아님 | **수정**: 24시간 초과는 요청 단위 `TimestampOutOfBoundsException`, 사전 차단 | SDK 문서 (Codex는 "불확실"로 표시) |
| Filter 키 대문자 여부 | **확정**: `LICENSE_ARN` 등. 가이드 표기도 허용 | SDK enum |
| 상태 우선순위, 차원별 만료, 미동기화 fail-closed | 수용 §5.1 | - |
| CONTRACT 수량 의미·리셋 기준, HYBRID 식, 원자성 | 수용 §5.2 | - |
| 버킷 UK, watermark, claim, attempts 의미, 부분 성공 | 수용 §7 | - |
| stale 캐시 최대 나이 | 수용 (15분 재동기화, 24시간 한도) | - |
| 데모 인증·admin API 보호 | 수용 (`DEMO_MODE` 마운트, 명시) | - |
| 일정 과다 → Dev Console/fault/로그/CI/영상/interval 하향 | 수용 §11 | - |
| README 독립 시간 확보 | 수용 (1h) | - |
| 이벤트명: SNS와 EventBridge 혼용 | 수용 → mock 이벤트 계약 + 대응표 | - |
| customerIdentifier 단독 PK 취약 | **강화**: 내부 ID + `licenseArn` UK | SDK: 2026-06 Concurrent Agreements (Codex 미언급) |
| `/api/subscribers` 토큰을 헤더로 전달 | **변경**: fulfillment 수신 즉시 resolve → onboardingId 사용 | SDK: 토큰 즉시 redeem 권고, 재제출 시 Expired |
| 등록 토큰 "통상 4시간" | **보류**: 확인 불가 → TTL은 설정값 [Mock 정책] | 근거 미확인 |
| Marketplace + 등록 화면 통합 | **부분 수용**: fulfillment가 서버 POST→302 구조라 라우트는 분리, 화면은 최소화 | - |
| AWS SDK 어댑터 가치 낮음 | 수용 (스텁 제거, README 설명만) | - |

## 13-2. 완성본 Codex 코드 리뷰 반영 내역 (2026-09-17)
| # | 지적 | 판정 | 조치 (재현 테스트 → 수정) |
|---|---|---|---|
| 1 | 권한 검사가 락 밖이라 해지 이벤트와 경합 | 수용 (P0는 과장, 좁은 경합) | 락 안에서 상태 재검사 · `UsageRaceTest` |
| 2 | 고객을 빠르게 바꾸면 늦은 응답이 화면을 덮음 | 수용 | 요청 순번으로 마지막 응답만 반영 · E2E |
| 3 | 순서가 뒤바뀐 이벤트가 최신 상태를 덮음 | 부분 수용 (주석이 순서를 보장한다고 쓰진 않았음) | `lastEventAt`보다 오래된 이벤트 무시 · `MarketplaceEventOrderTest` |
| 4 | 재구독 시 계약 기간이 이어짐 | 수용 | 해지 상태에서 시작 이벤트 → 새 기간 |
| 5 | 예상 못한 예외 시 SENDING 고착 | 수용 | finally로 되돌림, int 범위 초과는 사전 FAILED · `MeteringJobTest` |
| 6 | 0 보충 범위가 PLAN과 다름 | 문서만 수용 (가이드 근거로 의도한 결정) | §7 수정 |
| 7 | Mock이 요청당 제품 1개 제약을 검사 안 함 | 수용 | ValidationException · 계약 테스트 |
| 8 | 조작된 NextToken이 500 | 수용 | InvalidParameterException · 계약 테스트 |
| 9 | 이벤트 비밀값이 저장소에 고정 | 수용 | `APP_EVENT_SECRET`, 비데모 모드에서 기본값이면 기동 거부 · `AppPropertiesTest` |
| 10 | 초기화 중 동시 요청이 상태를 오염 | 수용 | `DemoStateLock`(요청=읽기, 초기화=쓰기) · `DemoStateLockTest` |
| 11 | 오류 응답에 역직렬화 내부 메시지 노출 | 수용 | 고정 문구 + 로그 |
| 12 | 프론트 경합 테스트 없음 | 수용 | E2E 추가 |
| 13 | 등록 폼 접근성 | 수용 | `aria-invalid`/`aria-describedby`, 첫 오류 칸 포커스 |

## 14. 결정 사항 (2026-09-17 확정)
1. **작업 위치**: `C:\dev\aws-marketplace-billing-mock` (OneDrive 밖, 영문 경로)
2. **스택**: Vue 3 + Spring Boot. 원래 TS 기준으로 잡았던 설계는 그대로 유지하고 구현 언어만 바꾼다(§2, §9 반영).
3. **레포**: public
