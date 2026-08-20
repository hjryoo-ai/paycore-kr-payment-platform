# PayCore-KR

> 기업 고객의 **원화 이체**를 접수해 검증 → 청산망 전송 → 상태 추적 → 원장 반영 → 일마감 대사까지
> 처리하는 미니 결제 허브. 화면이나 CRUD가 아니라 **비동기 메시징 위의 트랜잭션 정합성**을 다룬다.

설계 원본: [`docs/payment-platform-design.md`](docs/payment-platform-design.md) · 결정 기록: [`docs/adr/`](docs/adr)

## 이 프로젝트가 증명하는 것

1. **중복 지급을 만들지 않는다** — downstream timeout을 실패로 단정하지 않고 `UNKNOWN` 으로 두었다가
   `pacs.028` 상태 조회로 사실을 확인한 뒤에만 전이한다. blind resend는 금지다.
2. **DB 변경과 메시지 발행이 어긋나지 않는다** — dual-write 대신 Transactional Outbox.
3. **재처리가 안전하다** — 모든 consumer는 inbox(`PROCESSED_MESSAGE`) dedup + 비즈니스 키 UNIQUE 2중 방어.

## 아키텍처

```
기업고객 ──POST /payments──▶ payment-api ─┐
                              (+payment-core: 상태머신 · Outbox)
                                          │ Oracle 23ai
                                          ▼
                                  OUTBOX ──poller──▶ Kafka payment.events
                                                        │           │
                                            clearing-gateway   ledger-service
                                                 │ JMS               │
                                     CLR.REQ ────┴───▶ clearing-simulator
                                     CLR.RES ◀────────  (장애 주입 모드)
                                                            │ EOD CSV
                                                            ▼
                                                       recon-batch (3-way 대사)
```

| 모듈 | 역할 | 포트 |
|---|---|---|
| `common` | 메시지 계약(pacs.*), ID 생성, 에러코드, 마스킹 — *라이브러리* | — |
| `payment-core` | 상태머신 · 비즈니스 검증 · Outbox — *라이브러리* ([ADR-0003](docs/adr/0003-payment-core-runs-in-payment-api-process.md)) | — |
| `payment-api` | 접수/조회 API, 멱등성, Flyway 소유 ([ADR-0004](docs/adr/0004-single-schema-flyway-owned-by-payment-api.md)) | 8081 |
| `clearing-gateway` | pacs.008 송신 / pacs.002 수신 / timeout·inquiry | 8082 |
| `clearing-simulator` | 공동망+상대은행 시뮬레이터, 장애 주입 API | 8083 |
| `ledger-service` | 복식부기 원장 | 8084 |
| `recon-batch` | EOD 3-way 대사 | 8085 |

## 기술 스택

Java 21 · Spring Boot 4.1.0 · Gradle 9.3 (Kotlin DSL, 멀티모듈) · Oracle Database Free 23ai ·
Flyway · Apache Kafka 4.3 (KRaft) · JMS(ActiveMQ Artemis 기본 / IBM MQ 프로파일, [ADR-0002](docs/adr/0002-artemis-as-default-jms-broker.md)) ·
Testcontainers · Micrometer/Prometheus/Grafana

전체 버전은 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 한 곳에서 고정한다.

## 빠르게 띄우기

사전 요구: Docker Desktop(또는 호환 런타임), JDK 21. Gradle은 wrapper 사용.

```bash
# 1) 인프라만 (Oracle 최초 기동은 1~2분 걸린다)
docker compose up -d oracle kafka artemis
scripts/wait-for-healthy.sh

# 2) 애플리케이션 이미지 빌드 + 전체 스택
scripts/build-images.sh
docker compose up -d
scripts/wait-for-healthy.sh

# 3) 확인
curl -s localhost:8081/actuator/health   # payment-api
curl -s localhost:8082/actuator/health   # clearing-gateway
curl -s localhost:8083/actuator/health   # clearing-simulator
curl -s localhost:8084/actuator/health   # ledger-service
curl -s localhost:8085/actuator/health   # recon-batch
```

선택 프로파일:

```bash
docker compose --profile obs up -d      # Prometheus(9090) + Grafana(3000, admin/admin)
docker compose --profile ibmmq up -d    # IBM MQ (amd64 에뮬레이션 — ADR-0002)
```

접속 정보(로컬 전용): Oracle `paycore/paycore@//localhost:1521/FREEPDB1` ·
Artemis 콘솔 <http://localhost:8161> (`paycore/paycore`) · Kafka 호스트 리스너 `localhost:29092`

정리: `docker compose down -v`

## 개발

```bash
./gradlew build            # 컴파일 + 포맷 검사 + 테스트
./gradlew spotlessApply    # 포맷 자동 수정
./gradlew bootJar          # 실행 가능 jar
./gradlew dependencyCheckAnalyze   # OWASP 취약점 스캔 (NVD_API_KEY 환경변수 권장)
```

작업 규칙은 [`CLAUDE.md`](CLAUDE.md) 참고. 핵심 불변식 5가지가 테스트로 강제된다.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 스캐폴딩 · compose · 스키마 · CI 기반 | ✅ |
| 1 | 접수 API + 멱등성 | ✅ |
| 2 | 상태머신 + Outbox + Kafka | ✅ |
| 3 | 청산 연동 + 시뮬레이터 | ✅ |
| 4 | 복식부기 원장 | ⬜ |
| 5 | EOD 3-way 대사 | ⬜ |
| 6 | DLQ + 운영 repair | ⬜ |
| 7 | CI/CD + 관측성 | ⬜ |
| 8 | React 운영 대시보드 | ⬜ |
| 9 | 데모 스크립트 + 문서 마감 | ⬜ |

## 상태머신과 Outbox (Phase 2)

접수(`RECEIVED`)된 결제는 **커밋 후**(`@TransactionalEventListener(AFTER_COMMIT)`) 비동기로 검증된다.
검증 트랜잭션 하나 안에서 ① 결제 행 잠금 ② 비즈니스 검증 ③ 상태 전이 + 이력 ④ `OUTBOX_EVENT` insert
가 모두 일어난다 — 상태와 이벤트가 어긋날 창이 없다.

| 불변식 | 강제 지점 | 테스트 |
|---|---|---|
| 허용된 전이만 가능 | [`PaymentStateMachine`](payment-core/src/main/java/kr/paycore/core/statemachine/PaymentStateMachine.java) 전이표 | `PaymentStateMachineTest` — 9×9 = 81개 조합 전수 |
| 확정 상태 역행 금지 | 종결 상태(`REJECTED`/`FAILED`/`SETTLED`)는 출구 없음 | `CLEARED → UNKNOWN` 예외 검증 |
| 같은 상태 재적용은 no-op | `transition()` 이 `false` 반환, 이력 미기록 | 전수 테스트에 포함 |
| DB 변경 ↔ 이벤트 발행 원자성 | [`OutboxWriter`](payment-core/src/main/java/kr/paycore/core/outbox/OutboxWriter.java) 가 호출자 TX에 참여 (`KafkaTemplate` 직접 호출 없음) | `OutboxPublishingIT` |
| 발행 직전 크래시에도 유실 0 | poller 정지 상태에서 접수 → 재개 시 전량 발행 (시나리오 #6) | `OutboxCrashRecoveryIT` |

발행은 별도 poller([`OutboxPoller`](payment-core/src/main/java/kr/paycore/core/outbox/OutboxPoller.java) →
[`OutboxPublisher`](payment-core/src/main/java/kr/paycore/core/outbox/OutboxPublisher.java))가 맡는다.
`FOR UPDATE SKIP LOCKED` 로 여러 인스턴스가 같은 이벤트를 집지 않는다
([ADR-0007](docs/adr/0007-outbox-poller-over-cdc.md) — Oracle `ORA-02014` 회피 쿼리 포함).
결과는 at-least-once이며 중복은 consumer 멱등성으로 흡수한다.

일일 이체한도는 비관적 락으로 차감한다 ([ADR-0006](docs/adr/0006-pessimistic-lock-for-daily-limit.md)).
한도 초과·라우팅 불가 건은 `REJECTED` 로 전이하고 `PaymentRejected` 이벤트를 남긴다.

## 청산 연동과 UNKNOWN 처리 (Phase 3)

> **이 프로젝트에서 가장 방어하고 싶은 지점이다.** downstream 이 응답하지 않을 때 무엇을 하느냐가
> 결제 시스템의 수준을 가른다.

### 절대 규칙: timeout ≠ 실패

```
pacs.008 송신 → 10초 무응답
 └─ 상태 UNKNOWN (실패 아님, 재송신 금지)
     └─ pacs.028 상태조회 (backoff 10s / 30s / 60s)
         ├─ ACSC "처리했다"        → CLEARED    (돈은 한 번만 나갔다)
         ├─ RJCT/NOOR "받은 적 없다" → FAILED 확정 (이때만 재송신이 안전하다)
         ├─ RJCT/기타 "거절했다"     → FAILED    (재송신 불가)
         ├─ ACSP·PDNG "처리 중"     → 전이 없음. 다음 조회를 기다린다
         └─ 3회 모두 무응답          → MANUAL_REVIEW (추측하지 않고 사람을 부른다)
```

재송신을 하지 않는 대신 **"보낸 것을 모르는 상태"를 만들지 않는다**: 상태 전이 · `CLEARING_MESSAGE_LOG`
기록 · 아웃박스가 한 트랜잭션이고, JMS 송신은 그 커밋 **뒤에** 일어난다 ([ADR-0008](docs/adr/0008-clearing-gateway-embeds-payment-core.md)).
커밋 직후 죽어도 그 건은 timeout → 조회 → `NOOR` → `FAILED` 로 정확히 수렴한다.

### 겹쳐 놓은 방어선

| 위험 | 방어 | 테스트 |
|---|---|---|
| 응답 유실 후 재송신 → 이중 지급 | 재송신 경로 자체가 없다. 확인 수단은 pacs.028 뿐 | `TimeoutAndInquiryIT` #2 |
| 그래도 재송신됐다면 | 청산망이 같은 `endToEndId` 를 `DUPL` 로 거절 | `SimulatorModeIT` |
| 같은 pacs.002 2회 수신 | `PROCESSED_MESSAGE` inbox dedup | `ClearingIdempotencyIT` #4 |
| 늦게 온 모순 응답이 확정 상태를 덮음 | 상태머신이 거부 → 로그·알림만, 자동 변경 없음 | `ClearingIdempotencyIT` |
| 우리가 보낸 적 없는 메시지에 대한 응답 | `CLEARING_MESSAGE_LOG` 에 원 msgId 가 없으면 무시 | `ClearingIdempotencyIT` |
| 규격 위반 메시지 | JSON Schema 검증 — **보낼 때도** 검증한다 | `ClearingMessageCodecTest` |

### 메시지 계약

`common/src/main/resources/schemas/` 의 JSON Schema(2020-12)가 원본이고, 게이트웨이와 시뮬레이터가
같은 파일을 공유한다. DTO 는 [`kr.paycore.common.clearing`](common/src/main/java/kr/paycore/common/clearing).

| 메시지 | 역할 | 핵심 필드 |
|---|---|---|
| `pacs.008` | 이체 지시 | `grpHdr.msgId`(매 송신 새로 발급) · `pmtId.endToEndId`(**절대 불변**) |
| `pacs.002` | 상태 응답 | `txSts` = `ACSC`/`ACSP`/`PDNG`/`RJCT` · `orgnlMsgNmId` 로 조회 응답 구분 |
| `pacs.028` | 상태 조회 | 원 `msgId` 참조. "다시 보내줘"가 아니라 "그거 처리했어?" |

### 장애 주입 시뮬레이터

운영 API 로 동작 모드를 바꿔 시나리오를 **재현 가능하게** 만든다. 모드 목록과 사용법은
[`scripts/chaos/README.md`](scripts/chaos/README.md).

```bash
scripts/chaos/scenario-02-processed-but-no-response.sh   # UNKNOWN → inquiry → CLEARED
scripts/chaos/scenario-03-never-received.sh              # UNKNOWN → inquiry(NOOR) → FAILED
scripts/chaos/scenario-04-duplicate-response.sh          # 중복 응답 → 전이 1회
```

## API (Phase 1~3 구현분)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/v1/payments` | 이체 접수. `Idempotency-Key` 헤더 필수 → `202` + `{paymentId, endToEndId, status, acceptedAt}` |
| `GET` | `/api/v1/payments/{paymentId}` | 상태 + 상태 타임라인 (계좌번호 마스킹) |
| `GET` | `/api/v1/payments?status=&from=&to=&page=&size=` | 운영 조회 (기본 최근 7일, 기간은 `[from, to)`) |

```bash
KEY=$(uuidgen)
BODY='{"debtorAccount":"110-123-456789","creditorAccount":"352-987-654321",
       "creditorBankCode":"088","amount":1500000,"currency":"KRW","remittanceInfo":"8월 대금"}'

# 두 번 보내도 PAYMENT 는 1건, 응답 본문은 바이트 단위로 동일하다.
curl -si -X POST localhost:8081/api/v1/payments \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY"
```

멱등 재요청은 응답 헤더 `Idempotent-Replay: true` 로 구분된다. 같은 키에 **다른 본문**을 보내면
`422` + `PC-V003` 으로 거절한다 — 클라이언트 버그가 조용히 잘못된 이체가 되는 것을 막는다.
오류 응답은 전부 RFC 9457 `application/problem+json` 이며 내부 정보(스택트레이스·SQL)를 담지 않는다.

시뮬레이터 운영 API (`:8083`) — 장애 주입과 EOD:

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET`/`PUT` | `/simulator/mode` | 현재 모드 조회 / 변경 (`{"mode":"DELAY","delayMillis":20000}`) |
| `POST` | `/simulator/reset` | 처리 기록 초기화 + `NORMAL` 복귀 |
| `GET` | `/simulator/transfers[/{endToEndId}]` | 청산망이 아는 처리 내역 |
| `POST` | `/simulator/eod?date=YYYY-MM-DD` | EOD CSV 생성 (recon-batch 입력) |
| `GET` | `/simulator/eod/{date}` | EOD CSV 내려받기 |

## 단순화 선언 (실제 결제망과 다른 점)

실제 금융결제원/한은금융망 전문 규격은 비공개이므로 구현하지 않았다. 무엇을 단순화했는지 명시한다.

- **메시지 포맷**: ISO 20022 `pacs.008/002/028` 의 핵심 필드만 뽑은 자체 JSON([스키마](common/src/main/resources/schemas)). 이름과 개념은 실무를 따르되 규격은 자체 정의.
- **시뮬레이터 상태**: 처리 기록을 메모리에 둔다. 재기동하면 잊는다 — '상대편'이라 우리 스키마를 공유하지 않는다는 것을 드러내기 위한 선택이다.
- **시뮬레이터 모드**: 설계 §5.4 표에 `DROP_REQUEST` 를 추가했다 ([ADR-0009](docs/adr/0009-simulator-drop-request-mode.md)) — `DOWN`(큐에 쌓임)만으로는 '실제 미처리'를 재현할 수 없다.
- **차액결제**: 한은금융망 최종 결제 대신 시뮬레이터의 EOD 파일 생성으로 대체.
- **인증/인가**: API key 수준. 실제라면 mTLS + HSM 기반 메시지 서명 + 망분리 + 4-eyes 승인이 필요하다.
- **스키마**: 서비스별 분리 없이 단일 Oracle 스키마 ([ADR-0004](docs/adr/0004-single-schema-flyway-owned-by-payment-api.md)). 서비스가 늘면 스키마 분리 + 전용 마이그레이션 컨테이너로 진화.
- **시각 컬럼**: `TIMESTAMP WITH TIME ZONE` ([ADR-0005](docs/adr/0005-timestamp-with-time-zone.md)). 업무일자(`RECON_DATE`)만 `DATE`.
- **Outbox 발행**: 폴링 방식. 실무 규모에서는 Debezium CDC가 낫고, 그 진화 경로를 [ADR](docs/adr/)에 남긴다.
