# 장애 시나리오 재현 스크립트

`docs/payment-platform-design.md` §8 의 카탈로그를 **실행 가능한 형태**로 옮긴 것이다.
같은 시나리오가 통합 테스트로도 자동화되어 있으므로, 여기 스크립트는 "사람이 눈으로 보는" 용도다.

전제: `docker compose up -d` 로 전체 스택이 healthy 인 상태.

```bash
scripts/chaos/scenario-02-processed-but-no-response.sh   # 응답 유실, 실제로는 처리됨
scripts/chaos/scenario-03-never-received.sh              # 응답 유실, 실제로 미처리
scripts/chaos/scenario-04-duplicate-response.sh          # 중복 pacs.002
```

| # | 주입 | 기대 결과 | 대응 통합 테스트 |
|---|---|---|---|
| 2 | `PROCESS_BUT_NO_RESPONSE` | `UNKNOWN` → inquiry → `CLEARED`, 이체 1건, 재송신 0회 | `TimeoutAndInquiryIT#scenario2_processedButNoResponse` |
| 3 | `DROP_REQUEST` | `UNKNOWN` → inquiry(`NOOR`) → `FAILED` 확정 | `TimeoutAndInquiryIT#scenario3_neverReceived` |
| 4 | `DUPLICATE_RESPONSE` | 상태 전이 1회, 이벤트 1건 | `ClearingIdempotencyIT#scenario4_duplicateResponse` |
| 8 | `PROCESS_BUT_NO_RESPONSE` → `DOWN` 후 EOD | `MISSING_AT_US` break 생성 + md 리포트 | `ReconBatchIT#scenario8_unattendedUnknownIsDetected` |

시나리오 #1(이중 클릭)과 #6(발행 직전 크래시)은 각각 `PaymentIntakeIdempotencyIT`,
`OutboxCrashRecoveryIT` 로 자동화되어 있다. #5(consumer 강제 재소비)는 `LedgerIdempotencyIT`
가 실제로 오프셋을 되감아 재현한다. #7(poison message)은 Phase 6 에서 추가된다.

## 시뮬레이터 모드 API

```bash
curl -s localhost:8083/simulator/mode | python3 -m json.tool
curl -s -X PUT localhost:8083/simulator/mode -H 'Content-Type: application/json' \
     -d '{"mode":"DELAY","delayMillis":20000}'
curl -s -X POST localhost:8083/simulator/reset
curl -s localhost:8083/simulator/transfers | python3 -m json.tool
curl -s -X POST 'localhost:8083/simulator/eod?date=2026-08-20'
```

| 모드 | 동작 |
|---|---|
| `NORMAL` | 즉시 `ACSC` |
| `DELAY` | `delayMillis` 뒤 응답 |
| `PROCESS_BUT_NO_RESPONSE` | 처리하되 응답 없음 |
| `REJECT` | `rejectReason` 으로 `RJCT` |
| `DUPLICATE_RESPONSE` | 같은 pacs.002 2회 |
| `OUT_OF_ORDER` | 모아서 역순 송신 |
| `DOWN` | 큐 소비 중단 (메시지는 큐에 쌓임) |
| `DROP_REQUEST` | 이체 지시 유실, 조회에는 `NOOR` ([ADR-0009](../../docs/adr/0009-simulator-drop-request-mode.md)) |
