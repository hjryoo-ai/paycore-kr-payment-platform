# PayCore-KR

**A miniature KRW payment hub** that takes a corporate transfer request, validates it, sends it to the
clearing network, tracks its state, posts it to a double-entry ledger, and reconciles everything at
end of day. This is not a CRUD app with screens — it is about **transactional integrity on top of
asynchronous messaging**.

기업 고객의 **원화 이체**를 접수해 검증 → 청산망 전송 → 상태 추적 → 원장 반영 → 일마감 대사까지
처리하는 미니 결제 허브. 화면이나 CRUD가 아니라 **비동기 메시징 위의 트랜잭션 정합성**을 다룬다.

Design source of truth: [`docs/payment-platform-design.md`](docs/payment-platform-design.md) ·
Decisions: [`docs/adr/`](docs/adr) · Working rules: [`CLAUDE.md`](CLAUDE.md)

---

## What this project proves / 이 프로젝트가 증명하는 것

**1. Money never leaves twice.** A downstream timeout is not treated as a failure. The payment moves to
`UNKNOWN` and stays there until a `pacs.028` status inquiry establishes what actually happened.
Blind resend is forbidden — there is no code path that does it.

downstream timeout을 실패로 단정하지 않는다. `UNKNOWN`으로 두었다가 `pacs.028` 상태 조회로 사실을
확인한 뒤에만 전이한다. blind resend는 금지이며, 그렇게 하는 코드 경로 자체가 없다.

**2. A DB change and its event never disagree.** No dual-write. State transition, clearing message log,
and the outbox row commit together or not at all.

dual-write 대신 Transactional Outbox. 상태 전이·청산 메시지 기록·아웃박스가 함께 커밋되거나 함께 없다.

**3. Reprocessing is safe.** Every consumer passes an inbox (`PROCESSED_MESSAGE`) dedup *and* a business-key
UNIQUE constraint. Two independent defenses, tested independently.

모든 consumer는 inbox dedup + 비즈니스 키 UNIQUE 2중 방어를 거친다. 두 방어를 각각 따로 무력화해 검증한다.

---

## Architecture / 아키텍처

```
Corporate client ──POST /payments──▶ payment-api ─┐
                                     (+payment-core: state machine · outbox)
                                                  │ Oracle 23ai
                                                  ▼
                                          OUTBOX ──poller──▶ Kafka  payment.events
                                                                │            │
                                                    clearing-gateway    ledger-service
                                                         │ JMS               │
                                             CLR.REQ ────┴───▶ clearing-simulator
                                             CLR.RES ◀────────  (fault injection)
                                                                    │ EOD CSV
                                                                    ▼
                                                        recon-batch (3-way match)
                                                                    │
                                                            ops-dashboard
```

| Module | Role / 역할 | Port |
|---|---|---|
| `common` | Message contracts (`pacs.*`), ID generation, error codes, masking — *library* / 메시지 계약·ID·에러코드·마스킹 | — |
| `payment-core` | State machine · business validation · outbox — *library* ([ADR-0003](docs/adr/0003-payment-core-runs-in-payment-api-process.md)) | — |
| `payment-api` | Intake/query API, idempotency, owns Flyway ([ADR-0004](docs/adr/0004-single-schema-flyway-owned-by-payment-api.md)) | 8081 |
| `clearing-gateway` | `pacs.008` send · `pacs.002` receive · timeout · inquiry ([ADR-0008](docs/adr/0008-clearing-gateway-embeds-payment-core.md)) | 8082 |
| `clearing-simulator` | Clearing network + counterparty bank simulator with fault injection / 장애 주입 시뮬레이터 | 8083 |
| `ledger-service` | Double-entry ledger / 복식부기 원장 | 8084 |
| `recon-batch` | EOD 3-way reconciliation / 일마감 3-way 대사 | 8085 |
| `ops-dashboard` | Operator dashboard (React 19 + Vite + TS) / 운영 대시보드 | 8086 |

**Stack**: Java 21 · Spring Boot 4.1.0 · Gradle 9.3 (Kotlin DSL, multi-module) · Oracle Database Free 23ai ·
Flyway · Apache Kafka 4.3 (KRaft) · JMS (ActiveMQ Artemis default / IBM MQ profile,
[ADR-0002](docs/adr/0002-artemis-as-default-jms-broker.md)) · Testcontainers · Micrometer/Prometheus/Grafana ·
React 19 + Vite 8 + TypeScript 7

Every version is pinned in one place: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
버전은 이 파일 한 곳에서만 고정한다.

---

## Quick start / 빠르게 띄우기

Prerequisites: Docker Desktop (or compatible), JDK 21. Gradle via wrapper.

```bash
# 1) Infrastructure only (first Oracle boot takes 1–2 min)
docker compose up -d oracle kafka artemis
scripts/wait-for-healthy.sh

# 2) Build images + full stack
scripts/build-images.sh
docker compose up -d
scripts/wait-for-healthy.sh

# 3) See the whole thing work
scripts/demo.sh
```

`scripts/demo.sh` runs the happy path plus scenarios #2, #5, #8 and prints the EOD reconciliation report.
정상 흐름 1건 + 시나리오 #2·#5·#8 재현 + EOD 리포트 출력까지 한 번에 시연한다.

Optional profiles / 선택 프로파일:

```bash
docker compose --profile obs up -d      # Prometheus(9090) + Grafana(3000, admin/admin)
docker compose --profile ibmmq up -d    # IBM MQ (amd64 emulation — ADR-0002)
```

Local endpoints: dashboard <http://localhost:8086> · Oracle `paycore/paycore@//localhost:1521/FREEPDB1` ·
Artemis console <http://localhost:8161> (`paycore/paycore`) · Kafka host listener `localhost:29092`

Tear down: `docker compose down -v`

---

## The hard part: timeout ≠ failure / 핵심: timeout은 실패가 아니다

This is the single rule the whole system is organised around.
이 시스템 전체가 이 규칙 하나를 중심으로 짜여 있다.

```
pacs.008 sent → no response within 10s
 └─ status UNKNOWN            (not FAILED · no resend)
     └─ pacs.028 status inquiry, backoff 10s / 30s / 60s
         ├─ ACSC  "we processed it"        → CLEARED   (money left exactly once)
         ├─ RJCT/NOOR "never received it"  → FAILED    (only now is resend safe)
         ├─ RJCT/other "we rejected it"    → FAILED    (resend not permitted)
         ├─ ACSP · PDNG "in progress"      → no transition; ask again
         └─ 3 inquiries, no answer         → MANUAL_REVIEW (a human decides — we do not guess)
```

Instead of retrying, the system removes the state *"sent but we don't know it"*: the transition, the
`CLEARING_MESSAGE_LOG` row and the outbox event are one transaction, and the JMS send happens **after**
that commit. If the process dies right after committing, the payment converges through
timeout → inquiry → `NOOR` → `FAILED` without a single resend.

재송신을 하지 않는 대신 "보낸 것을 모르는 상태"를 없앴다. 상태 전이·송신 기록·아웃박스가 한 트랜잭션이고
JMS 송신은 그 커밋 뒤다. 커밋 직후 죽어도 timeout → 조회 → `NOOR` → `FAILED`로 재송신 없이 수렴한다.

### Layered defenses / 겹쳐 놓은 방어선

| Risk / 위험 | Defense / 방어 | Test |
|---|---|---|
| Resend after a lost response → double payment | No resend path exists; only `pacs.028` can confirm | `TimeoutAndInquiryIT` #2 |
| A resend happens anyway | Clearing network rejects a repeated `endToEndId` with `DUPL` | `SimulatorModeIT` |
| Same `pacs.002` delivered twice | `PROCESSED_MESSAGE` inbox dedup | `ClearingIdempotencyIT` #4 |
| Late contradicting response overwrites a settled state | State machine refuses; escalates to `MANUAL_REVIEW` + alert event | `ClearingIdempotencyIT` |
| Response citing a message we never sent | Correlation checks the log row **belongs to this payment** | `ClearingIdempotencyIT` |
| Malformed message | JSON Schema validated **on send as well as receive** | `ClearingMessageCodecTest` |
| Broken payload blocks a partition | Permanent errors go straight to DLT, no retry | `PoisonMessageDltIT` #7 |

---

## Design decisions worth defending / 설계 방어 지점

### State machine is the only owner of state / 상태의 유일한 소유자

Nine states, an explicit transition table, and **92 tests** covering all 9×9 combinations plus terminal
states and backward-transition bans. Re-applying the same state is a **no-op, not an error** — in
at-least-once messaging, receiving the same message twice is normal, and the state machine itself must
be the idempotency device.

전이표를 벗어난 전이는 예외다. 같은 상태 재적용은 예외가 아니라 no-op — at-least-once에서 같은 메시지를
두 번 받는 것은 정상이고, 그때 상태머신 자체가 멱등 장치가 되어야 한다.

`CLEARED` has exactly two exits: `SETTLED`, and `MANUAL_REVIEW` for the case where a late response
contradicts a decided state. That is deferring judgement to a human, not reversing it.

### Outbox, not dual-write / 아웃박스

State change and `OUTBOX_EVENT` insert share one local transaction; a separate poller publishes with
`FOR UPDATE SKIP LOCKED` ([ADR-0007](docs/adr/0007-outbox-poller-over-cdc.md), which also documents the
Oracle `ORA-02014` workaround). Delivery is at-least-once and duplicates are absorbed by consumer idempotency.
`KafkaTemplate` is never called from inside `payment-core`.

### Double-entry ledger / 복식부기 원장

| Direction | Account | Amount |
|---|---|---|
| Debit `D` | customer debtor account | 1,500,000 |
| Credit `C` | clearing suspense (`CLEARING_SUSPENSE`) | 1,500,000 |

Amounts are always **positive** and direction is carried by `DR_CR`. Encoding direction as a negative
amount lets one sign mistake silently pass the sum-zero check. The invariant is enforced in code *and*
by DB constraints. Balances are **derived** from entries, never stored — a stored balance that drifts
from the entries leaves nobody able to say which one is true.

금액은 항상 양수이고 방향은 `DR_CR`이 나타낸다. 음수로 방향을 표현하면 부호 실수 하나로 합계 0 검증이
조용히 통과한다. 잔액은 저장하지 않고 명세에서 유도한다.

### Three-way reconciliation / 3-way 대사

| Source | Knows what |
|---|---|
| `PAYMENT` | what **we** know |
| Clearing EOD CSV | what the **network** knows — produced from its own records, not ours |
| `JOURNAL` + `LEDGER_ENTRY` | what **accounting** knows |

| Break type | Meaning | Priority |
|---|---|---|
| `MISSING_AT_CLEARING` | we think it settled, the network has no record | 1 — possible phantom payment |
| `STATUS_MISMATCH` | both know it, outcomes disagree ([ADR-0010](docs/adr/0010-recon-scope-and-break-types.md)) | 2 |
| `MISSING_AT_US` | the network concluded, we did not — usually an abandoned `UNKNOWN` | 3 |
| `LEDGER_MISMATCH` | payment state and ledger disagree | 4 |
| `AMOUNT_MISMATCH` | the two sides know different amounts | 5 |

The matching logic ([`ReconEngine`](recon-batch/src/main/java/kr/paycore/recon/match/ReconEngine.java)) is a
**pure function** — no DB, no clock. Reconciliation rules must be verified exhaustively, and rules that
need a container to test cannot be verified exhaustively.

**Refusing to close is also a rule**: if the EOD file is missing or a single line is malformed, the batch
stops with an exception. Treating "couldn't fetch" as "zero records" would flag the entire day as
`MISSING_AT_CLEARING` and produce a result nobody can trust.

마감을 세우는 것도 규칙이다. 못 받은 것을 0건으로 처리하면 그날 전 건이 불일치로 잡혀 아무도 못 믿는
결과가 나온다 — 잘못된 대사 결과보다 멈춘 마감이 낫다.

### Permanent vs transient errors / 일시 오류와 영구 오류

This distinction *is* poison-message defense. Retrying a broken payload the same way you retry a DB
connection error lets one message block a partition forever, and every legitimate payment queued behind
it stops with it.

| Error | Handling |
|---|---|
| `PermanentMessageException`, deserialization failure | **straight to DLT, no retry** |
| Anything else (DB connection, …) | exponential backoff ×3, then DLT |

DLT messages are persisted into a worklist — a topic nobody looks at is not a worklist. The DLT consumer
**never runs business logic**: the moment it "tries once more" that is automatic re-injection, which
§7.5 forbids. Republishing is safe not because the operator is careful but because **every consumer goes
through inbox dedup**.

### Operator repair is not a bypass / repair는 예외 통로가 아니다

| Rule | Why |
|---|---|
| Only transitions already in the table | A bypass makes the transition table stop being the truth |
| `reason` is mandatory | A state change you cannot explain is indistinguishable from an incident |
| `X-Operator` header required | Anonymous intervention makes the audit log meaningless |
| Audit row commits **with** the state change | Never "changed it but there is no record" |
| Emits a downstream event | An operator's decision must reach the ledger, or reconciliation never closes |

---

## Observability / 관측성

### One key finds a payment's entire life / 결제 1건의 전 구간

Logs are scattered across processes, threads and time: intake in payment-api, dispatch on a
clearing-gateway scheduler, posting in a ledger-service consumer. Threading them together requires the
same key on every line — [`PaymentMdc`](payment-core/src/main/java/kr/paycore/core/observability/PaymentMdc.java)
puts `endToEndId` and `paymentId` into MDC.

`endToEndId` is the right key because it also travels **inside the clearing message** — it is the only
point where our logs and the counterparty's logs can be joined.

MDC is opened only via try-with-resources. Leaving it on a pooled thread attaches the wrong `endToEndId`
to the *next* payment's logs, and in an investigation a **wrong** log costs far more than a missing one.

MDC는 try-with-resources로만 연다. 풀 스레드에 남으면 다음 결제 로그에 엉뚱한 `endToEndId`가 붙는데,
사고 조사에서는 없는 로그보다 틀린 로그가 훨씬 오래 사람을 붙든다.

```bash
docker compose logs payment-api clearing-gateway ledger-service --no-color \
  | grep '"endToEndId":"PC01M0F…"'
```

### Metrics exist to drive alerts / 지표는 알림과 짝을 이룬다

Nothing is measured that nobody would act on. Every metric has an alert, and every alert says what to do.

| Metric | Alert | What the operator does |
|---|---|---|
| `paycore_payment_unknown_age_seconds` | > 5 min | Check `pacs.028` history → repair from worklist. **Never resend.** |
| `paycore_payment_count{status="MANUAL_REVIEW"}` | > 0 for 5 min | Repair with a reason via `/api/v1/ops/worklist` |
| `paycore_outbox_lag_seconds` | > 60 s | Poller/Kafka publish failing. Events are not lost. |
| `paycore_deadletter_open` | > 0 | Inspect cause, then republish |
| `paycore_recon_break_open` | > 0 | Follow the report's investigation order |
| `absent(paycore_recon_break_open)` | 10 min | **"Zero breaks" and "the batch never ran" are different things** |

Measurement choices matter: `UNKNOWN` dwell is a **maximum, not an average** — one abandoned payment is
the problem, and an average stays quiet while one item sits for days. Outbox is measured in **age, not
count** — 100 events just enqueued and 1 event stuck for 30 minutes are entirely different incidents.

Gauges are refreshed on a schedule rather than queried per scrape; if observation becomes load, it is
observation that dies first when load is high.

Grafana dashboard is provisioned from a file ([`docker/grafana/dashboards`](docker/grafana/dashboards)) —
a hand-imported dashboard exists only on one person's laptop.

---

## Operator dashboard / 운영 대시보드

Three screens only ([docs §5.7](docs/payment-platform-design.md)). The goal is not design; it is showing
**how an operator resolves an incident**.

| Screen | What it does |
|---|---|
| Payments | Search by status + timeline from `PAYMENT_STATUS_HISTORY`, showing **what triggered** each transition |
| Worklist | `MANUAL_REVIEW`/`UNKNOWN` payments and open DLT. Reason input and action buttons sit together; the audit trail appears immediately after acting |
| Reconciliation | Today's breaks sorted **in investigation order** — the types where money actually moved come first |

**Wording drives judgement.** The UI never calls `UNKNOWN` a failure; it says *"payment status unknown
(not a failure)"*, and that wording is pinned by a test. `FAILED`/`REJECTED` explicitly say "money did
not leave". If the screen shows `UNKNOWN` as a failure, the operator will treat it as one — and that is
precisely the incident §7.3 exists to prevent.

화면의 낱말이 운영자의 판단을 만든다. `UNKNOWN`을 "실패"라고 쓰지 않고 그 문구를 테스트로 고정했다.

`UNKNOWN` gets no repair button — the inquiry is still running, so it is not yet a human's call.
Buttons lock when the operator id or reason is empty: relying on server rejection alone means the
operator learns why only after clicking.

```bash
cd ops-dashboard && npm ci && npm run dev   # :5173, proxies to the backends
npm test
```

---

## Fault scenario catalogue / 장애 시나리오 카탈로그

All eight scenarios from the design are automated as tests; five also have runnable scripts.
설계 §8의 8종이 전부 테스트로 자동화돼 있고, 그중 5종은 실행 스크립트로도 재현된다.

| # | Scenario | Expected outcome | Test | Script |
|---|---|---|---|---|
| 1 | Double-clicked client | Same response, 1 payment row | `PaymentIntakeIdempotencyIT` | — |
| 2 | Response lost, actually processed | `UNKNOWN` → inquiry → `CLEARED`, 1 transfer, 0 resends | `TimeoutAndInquiryIT` | [`scenario-02`](scripts/chaos/scenario-02-processed-but-no-response.sh) |
| 3 | Response lost, never processed | `UNKNOWN` → inquiry(`NOOR`) → `FAILED` | `TimeoutAndInquiryIT` | [`scenario-03`](scripts/chaos/scenario-03-never-received.sh) |
| 4 | Duplicate `pacs.002` | One transition, one event | `ClearingIdempotencyIT` | [`scenario-04`](scripts/chaos/scenario-04-duplicate-response.sh) |
| 5 | Consumer crash + re-consume | One journal, same `journalId` | `LedgerIdempotencyIT` | [`demo.sh`](scripts/demo.sh) |
| 6 | Crash just before publishing | Poller publishes after restart, zero loss | `OutboxCrashRecoveryIT` | — |
| 7 | Poison message | Straight to DLT, other messages keep flowing | `PoisonMessageDltIT` | [`scenario-07`](scripts/chaos/scenario-07-poison-message.sh) |
| 8 | Abandoned `UNKNOWN` at EOD | `MISSING_AT_US` break + md report | `ReconBatchIT` | [`scenario-08`](scripts/chaos/scenario-08-eod-break.sh) |

Scenario #5 is not simulated — the test stops the listener, rewinds the consumer group offsets with
`AdminClient`, and restarts it, forcing genuine re-consumption of already-processed messages.

시나리오 #5는 흉내내지 않는다. 리스너를 세우고 오프셋을 실제로 되감아 재소비시킨다.

---

## API

### Intake & query (`:8081`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/payments` | Accept a transfer. `Idempotency-Key` required → `202` |
| `GET` | `/api/v1/payments/{paymentId}` | Status + timeline (accounts masked) |
| `GET` | `/api/v1/payments?status=&from=&to=&page=&size=` | Operator search (last 7 days by default, `[from, to)`) |

```bash
KEY=$(uuidgen)
BODY='{"debtorAccount":"110-123-456789","creditorAccount":"352-987-654321",
       "creditorBankCode":"088","amount":1500000,"currency":"KRW","remittanceInfo":"8월 대금"}'

# Send it twice: PAYMENT stays at 1 row and the response body is byte-identical.
curl -si -X POST localhost:8081/api/v1/payments \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d "$BODY"
```

An idempotent replay is marked by `Idempotent-Replay: true`. The **same key with a different body** is
rejected with `422` / `PC-V003` — this stops a client bug from quietly becoming a wrong transfer.
All errors are RFC 9457 `application/problem+json` and never carry stack traces or SQL.

같은 키에 다른 본문을 보내면 `422`로 거절한다 — 클라이언트 버그가 조용히 잘못된 이체가 되는 것을 막는다.

### Operations (`:8081`) — all require `X-Operator`, all audited

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/ops/worklist?status=` | Payments needing a human (default `MANUAL_REVIEW`) |
| `POST` | `/api/v1/ops/payments/{paymentId}/repair` | `{"decision":"CLEARED\|FAILED","reason":"…"}` |
| `GET` | `/api/v1/ops/dead-letters?status=` | DLT worklist |
| `POST` | `/api/v1/ops/dead-letters/{id}/republish` | After confirming the cause (never automatic) |
| `POST` | `/api/v1/ops/dead-letters/{id}/discard` | Decided not to reprocess — status changes, row stays |
| `GET` | `/api/v1/ops/audit?targetType=&targetId=` | Audit trail |

### Ledger (`:8084`) · Reconciliation (`:8085`) · Simulator (`:8083`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/ledger/journals/{paymentId}` | Journal + both entries (masked, includes `imbalance`) |
| `GET` | `/api/v1/ledger/accounts/{accountId}` | Debit/credit totals — derived, not stored |
| `GET` | `/api/v1/ledger/imbalance` | Global imbalance. Anything but `0` is an incident |
| `POST` | `/api/v1/recon/run?date=` | Run EOD reconciliation → summary + report path |
| `GET` | `/api/v1/recon/breaks?date=&status=` | Break list (dashboard input) |
| `GET`/`PUT` | `/simulator/mode` | Fault injection mode |
| `POST` | `/simulator/eod?date=` | Generate clearing EOD CSV |

Simulator modes: `NORMAL`, `DELAY`, `PROCESS_BUT_NO_RESPONSE`, `REJECT`, `DUPLICATE_RESPONSE`,
`OUT_OF_ORDER`, `DOWN`, `DROP_REQUEST` ([ADR-0009](docs/adr/0009-simulator-drop-request-mode.md)).
Details in [`scripts/chaos/README.md`](scripts/chaos/README.md).

---

## Development / 개발

```bash
docker compose down        # ← required before testing; see the warning below
./gradlew build            # compile + format check + tests
./gradlew spotlessApply    # auto-format
./gradlew bootJar          # runnable jars
./gradlew dependencyCheckAggregate   # OWASP scan (NVD_API_KEY recommended)
```

> **Do not run the compose stack and the test suite at the same time.** Integration tests spin up their
> own Oracle/Kafka/Artemis via Testcontainers. With the local stack running, Docker runs out of memory,
> the test Oracle is `OOMKilled`, and the symptom surfaces as a confusing
> `Could not initialize class …SharedContainers`. It is an environment problem, not a code one.
>
> compose 스택과 테스트를 동시에 돌리지 말 것. 테스트용 Oracle이 `OOMKilled` 되고, 증상은
> `SharedContainers` 초기화 실패라는 엉뚱한 오류로 나타난다.

Tests: **244 backend** (Testcontainers, no `Thread.sleep` — Awaitility only) + **19 frontend** (vitest).

### CI/CD

[`Jenkinsfile`](Jenkinsfile) and [`.github/workflows/ci.yml`](.github/workflows/ci.yml) run the same
stages. The ordering principle is **cheap checks first** — spending 20 minutes on Testcontainers only to
fail on a formatting violation is waste.

```
build → format → unit tests → integration tests → dashboard → dependency/secret scan → images → stack up + smoke
```

The smoke test ([`scripts/smoke-test.sh`](scripts/smoke-test.sh)) asks whether **money flowed all the way
through** — intake → clearing → ledger → zero reconciliation breaks — not merely whether the containers
came up. A health-check-only smoke test goes green with a broken pipeline.

스모크는 "떴는가"가 아니라 "돈이 끝까지 흘렀는가"를 본다.

### Security checklist / 보안 체크리스트

| Area | What is done | Where |
|---|---|---|
| Input | Bean Validation + whitelists (bank code, currency, account digit count); control characters barred from remittance info (log injection) | [`PaymentIntakeRequest`](payment-api/src/main/java/kr/paycore/api/payment/PaymentIntakeRequest.java) |
| Error responses | RFC 9457 problem+json; no stack traces, SQL or internal class names | [`GlobalExceptionHandler`](payment-api/src/main/java/kr/paycore/api/error/GlobalExceptionHandler.java) |
| Data | Account numbers never appear raw in logs, API responses or reconciliation reports | [`AccountMasker`](common/src/main/java/kr/paycore/common/mask/AccountMasker.java) |
| Message contract | JSON Schema validated **on send as well as receive** | [`ClearingMessageCodec`](common/src/main/java/kr/paycore/common/clearing/ClearingMessageCodec.java) |
| Secrets | Injected via env/compose only; gitleaks at pre-commit **and** in CI | [`.gitleaks.toml`](.gitleaks.toml), [`.pre-commit-config.yaml`](.pre-commit-config.yaml) |
| Dependencies | OWASP Dependency-Check, **build fails at CVSS ≥ 7** | [`build.gradle.kts`](build.gradle.kts) |
| Audit | Operator actions record who/when/why in the **same commit** as the state change | [`OperationAudit`](payment-core/src/main/java/kr/paycore/core/ops/OperationAudit.java) |

**If this were production, add**: mTLS · HSM-backed message signing · network segregation · four-eyes
approval · SSO/RBAC on operations APIs · encryption at rest for PII · key rotation · WORM audit storage.

---

## Interview Q&A / 면접 Q&A

**Q1. A downstream call times out. Why not just retry?**
**Q1. downstream 응답이 없으면 그냥 재시도하면 되지 않나요?**

Because a timeout tells you nothing about whether the money moved. Retrying a transfer whose outcome is
unknown is how double payments happen. The system moves the payment to `UNKNOWN` and asks
`pacs.028` — *"did you process this?"* — rather than sending the transfer again. A resend is only
considered after the network answers `NOOR` ("no original transaction received"), and even then it is a
policy decision, not an automatic action. Three defenses back this up: the inquiry itself, the network's
`endToEndId` duplicate rejection, and EOD reconciliation catching anything that slipped through.
Code: [`ClearingTimeoutService`](clearing-gateway/src/main/java/kr/paycore/gateway/inquiry/ClearingTimeoutService.java),
[`InquiryService`](clearing-gateway/src/main/java/kr/paycore/gateway/inquiry/InquiryService.java) ·
Test: `TimeoutAndInquiryIT`.

timeout은 돈이 움직였는지에 대해 아무것도 말해 주지 않는다. 결과를 모르는 이체를 다시 보내는 것이
이중 지급이 생기는 방식이다. 재송신은 청산망이 `NOOR`로 미수신을 확인해 준 뒤에야 논의 대상이 된다.

**Q2. How do you keep a DB change and its Kafka event consistent?**
**Q2. DB 변경과 Kafka 발행의 정합성은 어떻게 맞추나요?**

You cannot make them atomic, so we do not try. The state transition and the `OUTBOX_EVENT` insert share
one local transaction; a poller publishes committed rows using `FOR UPDATE SKIP LOCKED`. That yields
at-least-once delivery, and duplicates are absorbed by consumer idempotency — inbox dedup on the
technical key plus a UNIQUE business key. The alternative we rejected was CDC (Debezium): better at real
scale, too much operational surface for this size, and the evolution path is written down in
[ADR-0007](docs/adr/0007-outbox-poller-over-cdc.md). One trap worth mentioning: the poller and the
transactional publisher must be **separate beans**, because a self-invoked `@Transactional` method
bypasses the proxy, leaves entities detached, and silently republishes the same events forever.

원자적으로 만들 수 없으므로 시도하지 않는다. 상태 변경과 아웃박스 insert를 한 트랜잭션에 묶고 별도 poller가
발행한다. 결과는 at-least-once이며 중복은 소비자 멱등성이 흡수한다.

**Q3. A message is malformed. What happens to everything queued behind it?**
**Q3. 깨진 메시지 하나가 뒤에 줄 선 정상 결제를 막지 않나요?**

That is exactly why permanent and transient errors are classified differently. A deserialization failure
or contract violation goes to the DLT immediately with **zero retries**; a DB connection error is retried
with exponential backoff three times first. Retrying a broken payload the way you retry a transient fault
is what blocks a partition forever. DLT messages are persisted into an operator worklist — a topic nobody
looks at is not a worklist — and the DLT consumer never runs business logic, because "trying once more"
is automatic re-injection. Republishing is safe structurally, not by care: every consumer passes inbox
dedup. Code:
[`KafkaErrorHandlingConfig`](payment-core/src/main/java/kr/paycore/core/messaging/KafkaErrorHandlingConfig.java) ·
Test: `PoisonMessageDltIT`.

영구 오류와 일시 오류를 구분하는 것이 poison message 방어의 전부다. 깨진 payload를 DB 커넥션 오류와
똑같이 재시도하면 그 메시지 하나가 파티션을 영원히 막는다.

---

## Simplifications / 단순화 선언

Real Korean clearing (KFTC / BOK-Wire) message specifications are not public, so they are not implemented.
What was simplified is stated explicitly.

실제 금융결제원/한은금융망 전문 규격은 비공개이므로 구현하지 않았다. 무엇을 단순화했는지 명시한다.

- **Message format** — a self-defined JSON carrying only the core fields of ISO 20022 `pacs.008/002/028`
  ([schemas](common/src/main/resources/schemas)). Names and concepts follow practice; the specification is ours.
- **Net settlement** — replaced by the simulator's EOD file instead of BOK-Wire final settlement.
- **Simulator state** — processed records are held in memory and forgotten on restart. Deliberate: it is
  *the counterparty*, and it should not share our schema.
- **Simulator modes** — `DROP_REQUEST` was added to the design's §5.4 table
  ([ADR-0009](docs/adr/0009-simulator-drop-request-mode.md)); `DOWN` alone (messages queue up) cannot
  reproduce "genuinely never processed".
- **Auth** — operations APIs identify the actor via an `X-Operator` header only. Production needs SSO +
  RBAC + four-eyes approval. What is kept here is the one thing that matters: **who did it is always recorded**.
- **Schema** — a single Oracle schema rather than per-service
  ([ADR-0004](docs/adr/0004-single-schema-flyway-owned-by-payment-api.md)).
- **Timestamps** — `TIMESTAMP WITH TIME ZONE` ([ADR-0005](docs/adr/0005-timestamp-with-time-zone.md));
  only the business date (`RECON_DATE`) is a `DATE`.
- **Outbox publishing** — polling. CDC is better at real scale; the evolution path is in the ADRs.

---

## Status / 진행 상황

| Phase | Contents | Status |
|---|---|---|
| 0 | Scaffolding · compose · schema · CI base | ✅ |
| 1 | Intake API + idempotency | ✅ |
| 2 | State machine + outbox + Kafka | ✅ |
| 3 | Clearing integration + simulator | ✅ |
| 4 | Double-entry ledger | ✅ |
| 5 | EOD 3-way reconciliation | ✅ |
| 6 | DLQ + operator repair | ✅ |
| 7 | CI/CD + observability | ✅ |
| 8 | React operations dashboard | ✅ |
| 9 | Demo script + documentation | ✅ |
