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
docker compose down        # ← 테스트 전에 반드시. 아래 주의사항 참고
./gradlew build            # 컴파일 + 포맷 검사 + 테스트
./gradlew spotlessApply    # 포맷 자동 수정
./gradlew bootJar          # 실행 가능 jar
./gradlew dependencyCheckAggregate   # OWASP 취약점 스캔 (NVD_API_KEY 환경변수 권장)
```

> **compose 스택과 테스트를 동시에 돌리지 말 것.** 통합 테스트는 Testcontainers 로 Oracle·Kafka·Artemis 를
> 따로 띄운다. 로컬 스택이 떠 있으면 Docker 메모리가 모자라 테스트용 Oracle 이 `OOMKilled` 되고,
> 증상은 `Could not initialize class ...SharedContainers` 라는 엉뚱한 오류로 나타난다.
> 코드가 아니라 환경 문제이니 `docker compose down` 먼저 하고 다시 돌린다.

작업 규칙은 [`CLAUDE.md`](CLAUDE.md) 참고. 핵심 불변식 5가지가 테스트로 강제된다.

## 진행 상황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 스캐폴딩 · compose · 스키마 · CI 기반 | ✅ |
| 1 | 접수 API + 멱등성 | ✅ |
| 2 | 상태머신 + Outbox + Kafka | ✅ |
| 3 | 청산 연동 + 시뮬레이터 | ✅ |
| 4 | 복식부기 원장 | ✅ |
| 5 | EOD 3-way 대사 | ✅ |
| 6 | DLQ + 운영 repair | ✅ |
| 7 | CI/CD + 관측성 | ✅ |
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

## 복식부기 원장 (Phase 4)

이체 1건은 분개 1벌, 명세 2줄이 된다.

| 방향 | 계정 | 금액 |
|---|---|---|
| 차변 `D` | 고객 출금계좌 | 1,500,000 |
| 대변 `C` | 청산미결제(`CLEARING_SUSPENSE`) | 1,500,000 |

금액은 항상 **양수**이고 방향은 `DR_CR` 이 나타낸다. 음수 금액으로 방향을 표현하면 합계 0 검증이
부호 실수 하나로 조용히 통과한다. 합계 0 은 코드와 DB `CHECK` 양쪽에서 막는다.

### 재소비돼도 분개는 한 벌

| 방어 | 무엇을 막는가 | 테스트 |
|---|---|---|
| `PROCESSED_MESSAGE` inbox | 같은 메시지의 재전달 | `LedgerIdempotencyIT` |
| `JOURNAL(PAYMENT_ID)` UNIQUE | 메시지 ID 가 달라도(아웃박스 재발행) 같은 결제 | `LedgerIdempotencyIT` |

**시나리오 #5** 는 흉내내지 않고 실제로 재현한다 — 리스너를 세우고 `AdminClient` 로 소비자 그룹
오프셋을 0 으로 되감은 뒤 다시 띄워 이미 처리한 메시지를 강제로 재소비시킨다. 그래도 분개는
그대로이고 `journalId` 까지 동일하다.

`CLEARED → SETTLED` 전이는 원장이 아니라 payment-api 가 한다. 원장은 기록하는 쪽이지 결제 상태를
소유하는 쪽이 아니다. 잔액도 저장하지 않고 `LEDGER_ENTRY` 합계에서 유도한다 — 잔액 컬럼을 따로 두면
그것과 명세가 어긋나는 순간 어느 쪽이 진실인지 아무도 모른다.

## 일마감 3-way 대사 (Phase 5)

세 주장을 맞춰 본다. 어느 하나를 진실로 놓고 나머지를 맞추는 것이 아니라, **세 출처가 각자 무엇을
안다고 말하는지**를 나란히 놓는다.

| 출처 | 무엇을 아는가 |
|---|---|
| `PAYMENT` | 우리가 아는 것 |
| 청산망 EOD CSV | 청산망이 아는 것 — 우리 DB 를 보고 만든 값이 아니다 |
| `JOURNAL` + `LEDGER_ENTRY` | 회계가 아는 것 |

| break 유형 | 의미 | 조사 우선순위 |
|---|---|---|
| `MISSING_AT_CLEARING` | 우리는 지급 완료로 아는데 청산망 파일에 없음 | 1 — 유령 지급일 수 있다 |
| `STATUS_MISMATCH` | 양쪽이 같은 이체의 결과를 다르게 말함 ([ADR-0010](docs/adr/0010-recon-scope-and-break-types.md)) | 2 |
| `MISSING_AT_US` | 청산망에는 결론이 있는데 우리는 미확정 — **방치된 `UNKNOWN`** (시나리오 #8) | 3 |
| `LEDGER_MISMATCH` | 결제 상태와 원장이 어긋남 (분개 누락·합계 불일치·금액 불일치) | 4 |
| `AMOUNT_MISMATCH` | 양쪽이 아는 금액이 다름 | 5 |

판정 로직([`ReconEngine`](recon-batch/src/main/java/kr/paycore/recon/match/ReconEngine.java))은 **순수 함수**다 —
DB 도 시계도 만지지 않는다. 대사 규칙은 조합을 전수로 확인해야 하고, 컨테이너를 띄워야만 검증되는
규칙은 전수로 확인할 수 없다.

마감을 **세우는** 경우도 규칙이다: EOD 파일을 못 받았거나 한 줄이라도 깨져 있으면 예외로 멈춘다.
못 받은 것을 0건으로 처리하면 그날 전 건이 `MISSING_AT_CLEARING` 으로 잡혀 아무도 못 믿는 결과가 나온다.

재실행하면 그 날짜의 `OPEN` 만 교체하고 `RESOLVED` 는 남긴다 — 운영자가 처리한 기록을 배치가 지우면
같은 건을 매일 처음부터 다시 조사하게 된다.

## 실패한 메시지와 사람의 개입 (Phase 6)

### 일시 오류와 영구 오류를 구분한다

이 구분이 poison message 방어의 전부다. 깨진 payload 를 DB 커넥션 오류와 똑같이 재시도하면
**그 메시지 하나가 파티션을 영원히 막고 뒤에 줄 선 정상 결제들이 함께 멈춘다.**

| 오류 | 처리 |
|---|---|
| `PermanentMessageException` · 역직렬화 실패 | **재시도 없이 즉시 DLT** |
| 그 밖(DB 커넥션 등) | 지수 backoff 로 3회 재시도 후 DLT |

DLT 토픽(`payment.events.DLT`)은 명시 선언한다. 실패 레코드를 원본과 같은 파티션 번호로 보내는
정책이라 파티션 수가 모자라면 그대로 실패하기 때문이다.

### DLT 는 보이는 곳에 있어야 처리된다

토픽에만 두면 아무도 보지 않는다. DLT 전용 소비자가 `DEAD_LETTER` 테이블에 적재하고 워크리스트로
노출한다. 이 소비자는 **비즈니스 로직을 절대 실행하지 않는다** — "한 번 더 해보는" 순간 그것이
자동 재주입이고, §7.5 가 금지하는 것이다.

재발행이 안전한 이유는 운영자가 조심해서가 아니라 **구조** 때문이다: 모든 소비자가
`PROCESSED_MESSAGE` inbox 를 거치므로 이미 처리된 메시지가 다시 들어와도 두 번 처리되지 않는다.

### repair 는 예외 통로가 아니다

| 규칙 | 이유 |
|---|---|
| 전이표에 있는 길만 간다 | 상태머신을 우회하는 통로를 만들면 전이표는 더 이상 진실이 아니다 |
| 근거(`reason`) 필수 | 설명할 수 없는 상태 변경은 사고와 구분되지 않는다 |
| `X-Operator` 헤더 필수 | 익명 개입을 허용하는 순간 감사 로그가 무의미해진다 |
| 감사 기록이 **같은 커밋** | "바꿨는데 기록이 없다"를 만들지 않는다 — 아웃박스·inbox 와 같은 이유 |
| 확정 후 하류로 이벤트 발행 | 운영자 확정이 원장까지 이어져야 대사가 닫힌다 |

**시나리오 #7**: 깨진 payload 주입 → 재시도 없이 DLT → 같은 파티션의 뒤이은 결제는 정상 처리
(`PoisonMessageDltIT`, `scripts/chaos/scenario-07-poison-message.sh`).

## 관측성 (Phase 7)

### 결제 1건의 전 구간 로그를 한 번에 찾는다

로그는 여러 프로세스·여러 스레드·여러 시각에 흩어진다. 접수는 payment-api 가, 송신은
clearing-gateway 스케줄러가, 기표는 ledger-service 소비자가 남긴다. 그걸 시간순으로 꿰려면
모든 줄에 같은 키가 있어야 한다 — [`PaymentMdc`](payment-core/src/main/java/kr/paycore/core/observability/PaymentMdc.java)
가 `endToEndId` 와 `paymentId` 를 MDC 에 넣는다.

`endToEndId` 를 키로 고른 이유: 청산망 메시지에도 실려 나가므로 **우리 로그와 상대 로그를 잇는
유일한 지점**이다.

MDC 는 try-with-resources 로만 연다. 풀에서 재사용되는 스레드에 남기면 다음 결제의 로그에 엉뚱한
`endToEndId` 가 붙는데, 사고 조사에서 없는 로그보다 **틀린 로그**가 훨씬 오래 사람을 붙든다
(`PaymentMdcTest` 가 누수·중첩·예외 경로를 모두 고정한다).

로그는 Boot 4 내장 구조화 로깅으로 ECS JSON 이다. `PAYCORE_LOG_FORMAT=` 로 비우면 평문으로 돌아간다.

```bash
docker compose logs payment-api clearing-gateway ledger-service --no-color \
  | grep '"endToEndId":"PC01M0F...' | jq -r '.["@timestamp"] + " " + .message'
```

### 지표는 알림 규칙과 짝을 이룬다

아무도 보지 않을 지표는 넣지 않았다. 각 지표에는 대응하는 알림이 있고, 각 알림에는 **지금 무엇을
하라는 것인지**가 적혀 있다.

| 지표 | 알림 조건 | 사람이 할 일 |
|---|---|---|
| `paycore_payment_unknown_age_seconds` | > 5분 | pacs.028 조회 이력 확인 → 워크리스트에서 repair. **재송신 금지** |
| `paycore_payment_count{status="MANUAL_REVIEW"}` | > 0 (5분) | `/api/v1/ops/worklist` 에서 근거와 함께 repair |
| `paycore_outbox_lag_seconds` | > 60초 | poller/Kafka 발행 실패 확인. 이벤트는 유실되지 않는다 |
| `paycore_deadletter_open` | > 0 | `/api/v1/ops/dead-letters` 에서 원인 확인 후 republish |
| `paycore_recon_break_open` | > 0 | 대사 리포트의 조사 순서를 따른다 |
| `absent(paycore_recon_break_open)` | 10분 | **불일치 0건과 '마감이 안 돌았음'은 다르다** |

측정 방식에도 이유가 있다. UNKNOWN 체류는 평균이 아니라 **최댓값**이다 — 방치된 한 건이 문제이지
전체 평균이 문제인 적은 없고, 평균은 한 건이 며칠 묵어도 조용하다. outbox 는 건수가 아니라
**나이**를 본다 — 100건이 방금 쌓인 것과 1건이 30분째 못 나가는 것은 완전히 다른 사고다.

게이지는 스크레이프마다 DB 를 때리지 않고 주기 조회 결과를 담아 둔다. 관측이 부하가 되면
부하가 심할 때 관측이 먼저 끊긴다.

Grafana 대시보드 1장이 파일로 프로비저닝된다
([`docker/grafana/dashboards`](docker/grafana/dashboards)) — 손으로 import 하면 그 사람 노트북에만 남는다.

```bash
docker compose --profile obs up -d     # Prometheus :9090 · Grafana :3000 (admin/admin)
```

## CI/CD (Phase 7)

[`Jenkinsfile`](Jenkinsfile) 과 [`.github/workflows/ci.yml`](.github/workflows/ci.yml) 이 **같은 단계**를
돈다. 순서 원칙은 하나다 — **싼 검증을 먼저**. 포맷 위반 하나 때문에 Oracle 컨테이너를 띄우는
통합 테스트를 20분 돌리고 나서 실패하는 것은 낭비다.

```
빌드 → 포맷 → 단위 테스트 → 통합 테스트(Testcontainers) → 의존성/시크릿 스캔 → 이미지 → 스택 기동 + 스모크
```

스모크([`scripts/smoke-test.sh`](scripts/smoke-test.sh))는 "떴는가"가 아니라 **돈이 끝까지 흘렀는가**를
본다: 접수 → 청산 → 원장 → 대사 불일치 0건. 헬스체크만 보는 스모크는 파이프라인이 깨진 채로도
초록불이 켜진다.

## 보안 체크리스트 (docs §10.2)

| 영역 | 무엇을 하는가 | 어디에 |
|---|---|---|
| 입력 | Bean Validation + 화이트리스트(은행코드·통화·계좌 자릿수), 적요 제어문자 차단(로그 인젝션) | [`PaymentIntakeRequest`](payment-api/src/main/java/kr/paycore/api/payment/PaymentIntakeRequest.java) |
| 오류 응답 | RFC 9457 problem+json, 스택트레이스·SQL·내부 클래스명 비노출 | [`GlobalExceptionHandler`](payment-api/src/main/java/kr/paycore/api/error/GlobalExceptionHandler.java) |
| 데이터 | 계좌번호는 로그·API 응답·대사 리포트 어디서도 원문 노출 없음 | [`AccountMasker`](common/src/main/java/kr/paycore/common/mask/AccountMasker.java) |
| 메시지 계약 | JSON Schema 검증을 **송신 시에도** 수행 | [`ClearingMessageCodec`](common/src/main/java/kr/paycore/common/clearing/ClearingMessageCodec.java) |
| 시크릿 | 환경변수/compose 로만 주입, gitleaks pre-commit + CI 이중 게이트 | [`.gitleaks.toml`](.gitleaks.toml), [`.pre-commit-config.yaml`](.pre-commit-config.yaml) |
| 의존성 | OWASP Dependency-Check, **CVSS 7 이상이면 빌드 실패** | [`build.gradle.kts`](build.gradle.kts) |
| 감사 | 운영자 행위는 who/when/why 가 상태 변경과 **같은 커밋**에 기록 | [`OperationAudit`](payment-core/src/main/java/kr/paycore/core/ops/OperationAudit.java) |

**실운영이라면 추가할 것**: mTLS · HSM 기반 메시지 서명 · 망분리 · 4-eyes 승인 ·
운영 API SSO/RBAC · 개인정보 암호화(TDE 또는 컬럼 암호화) · 키 로테이션 · WORM 감사 저장소.

## API (Phase 1~7 구현분)

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

원장 조회 API (`:8084`):

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/v1/ledger/journals/{paymentId}` | 분개 + 명세 2줄 (계좌 마스킹, `imbalance` 포함) |
| `GET` | `/api/v1/ledger/accounts/{accountId}` | 차변/대변 합계와 순액 — 저장값이 아니라 유도값 |
| `GET` | `/api/v1/ledger/imbalance` | 전체 장부 불균형. `0` 이 아니면 즉시 조사 대상 |

대사 API (`:8085`):

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/v1/recon/run?date=YYYY-MM-DD` | 일마감 대사 실행 → 요약 + 리포트 경로 |
| `GET` | `/api/v1/recon/breaks?date=&status=` | 불일치 목록 (대시보드 입력) |

운영 API (`:8081`) — 모두 `X-Operator` 헤더 필수, 모두 감사 기록:

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/v1/ops/worklist?status=` | 사람이 봐야 하는 결제 (기본 `MANUAL_REVIEW`) |
| `POST` | `/api/v1/ops/payments/{paymentId}/repair` | `{"decision":"CLEARED\|FAILED","reason":"..."}` |
| `GET` | `/api/v1/ops/dead-letters?status=` | DLT 워크리스트 |
| `POST` | `/api/v1/ops/dead-letters/{id}/republish` | 원인 확인 후 재발행 (자동 재주입 금지) |
| `POST` | `/api/v1/ops/dead-letters/{id}/discard` | 재처리하지 않기로 함 — 지우지 않고 상태만 바꾼다 |
| `GET` | `/api/v1/ops/audit?targetType=&targetId=` | 감사 추적 |

## 단순화 선언 (실제 결제망과 다른 점)

실제 금융결제원/한은금융망 전문 규격은 비공개이므로 구현하지 않았다. 무엇을 단순화했는지 명시한다.

- **메시지 포맷**: ISO 20022 `pacs.008/002/028` 의 핵심 필드만 뽑은 자체 JSON([스키마](common/src/main/resources/schemas)). 이름과 개념은 실무를 따르되 규격은 자체 정의.
- **시뮬레이터 상태**: 처리 기록을 메모리에 둔다. 재기동하면 잊는다 — '상대편'이라 우리 스키마를 공유하지 않는다는 것을 드러내기 위한 선택이다.
- **시뮬레이터 모드**: 설계 §5.4 표에 `DROP_REQUEST` 를 추가했다 ([ADR-0009](docs/adr/0009-simulator-drop-request-mode.md)) — `DOWN`(큐에 쌓임)만으로는 '실제 미처리'를 재현할 수 없다.
- **차액결제**: 한은금융망 최종 결제 대신 시뮬레이터의 EOD 파일 생성으로 대체.
- **인증/인가**: 운영 API 는 `X-Operator` 헤더로 행위자만 식별한다. 실제라면 SSO + 권한 + 4-eyes 승인, 그리고 mTLS + HSM 기반 메시지 서명 + 망분리가 필요하다. 여기서는 **누가 했는지가 반드시 남는다**는 점만 지킨다.
- **스키마**: 서비스별 분리 없이 단일 Oracle 스키마 ([ADR-0004](docs/adr/0004-single-schema-flyway-owned-by-payment-api.md)). 서비스가 늘면 스키마 분리 + 전용 마이그레이션 컨테이너로 진화.
- **시각 컬럼**: `TIMESTAMP WITH TIME ZONE` ([ADR-0005](docs/adr/0005-timestamp-with-time-zone.md)). 업무일자(`RECON_DATE`)만 `DATE`.
- **Outbox 발행**: 폴링 방식. 실무 규모에서는 Debezium CDC가 낫고, 그 진화 경로를 [ADR](docs/adr/)에 남긴다.
