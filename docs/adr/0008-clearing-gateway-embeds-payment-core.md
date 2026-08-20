# ADR-0008: clearing-gateway 는 payment-core 를 임베드해 직접 상태를 전이한다

- 상태: 채택
- 일자: 2026-08-20
- 관련: [ADR-0003](0003-payment-core-runs-in-payment-api-process.md), `docs/payment-platform-design.md` §4.1 · §5.3, CLAUDE.md 불변식 2·3

## 맥락

설계 §4.1 시퀀스 다이어그램은 두 가지를 섞어서 그리고 있다.

```
G->>DB: SENT_TO_CLEARING + CLEARING_MESSAGE_LOG      ← 게이트웨이가 DB 를 직접 바꾼다
G->>K:  PaymentCleared → K->>CO: CLEARED 상태 갱신    ← 게이트웨이가 이벤트를 내고 core 가 바꾼다
```

같은 결제의 상태를 어떤 전이는 게이트웨이가, 어떤 전이는 core 가 바꾸게 되어 있다.
Phase 3 을 시작하며 이 부분을 한쪽으로 정해야 했다.

## 결정

**clearing-gateway 도 `payment-core` 를 라이브러리로 임베드하고, 청산 구간의 모든 상태 전이를
게이트웨이 프로세스 안에서 `PaymentStateMachine` 을 통해 직접 수행한다.**

게이트웨이가 소유하는 전이는 네 가지다.

| 계기 | 전이 |
|---|---|
| `PaymentValidated` 소비 → pacs.008 송신 준비 | `VALIDATED → SENT_TO_CLEARING` |
| pacs.002 `ACSC` 수신 | `SENT_TO_CLEARING/UNKNOWN → CLEARED` |
| pacs.002 `RJCT` 수신 | `SENT_TO_CLEARING/UNKNOWN → FAILED` |
| 응답 timeout / inquiry 반복 실패 | `SENT_TO_CLEARING → UNKNOWN`, `UNKNOWN → MANUAL_REVIEW` |

## 근거

**1. "보낸 것을 모르는 상태"를 만들지 않기 위해서다.** §5.3 은 *송신 전에* `CLEARING_MESSAGE_LOG` 를
insert 하라고 못박는다. 게이트웨이가 Kafka 로 명령을 보내고 payment-api 가 상태를 바꾸는 구조라면,
게이트웨이는 `SENT_TO_CLEARING` 이 기록됐는지 모르는 채로 pacs.008 을 송신해야 한다. 그 사이에
죽으면 **돈은 나갔는데 우리 DB 에는 아무 흔적이 없는** 최악의 상태가 된다. 임베드하면
`상태 전이 + CLEARING_MESSAGE_LOG + OUTBOX` 가 한 트랜잭션이고, 송신은 커밋 이후다.

**2. 상태 전이의 구현은 여전히 하나다.** 불변식 2 가 요구하는 것은 "전이가 한 *프로세스*에서
일어난다"가 아니라 "전이가 `PaymentStateMachine` 전이표를 벗어나지 않는다"이다. 라이브러리를
공유하면 전이표·이력 기록·예외 처리가 물리적으로 같은 코드다. 서비스로 쪼개면 오히려 전이 규칙이
두 벌 생길 위험이 있다.

**3. 커밋 후 송신 중 크래시는 inquiry 경로가 이미 처리한다.** `SENT_TO_CLEARING` 으로 커밋된 뒤
JMS 송신 전에 죽으면 그 건은 timeout → `UNKNOWN` → pacs.028 → 청산망이 "받은 적 없음(NOOR)" 응답 →
`FAILED` 확정으로 흘러간다. blind resend 없이 정확히 수렴한다(§7.3).

## 기각한 대안

- **게이트웨이 → Kafka 명령 → payment-core 적용**: 위 1번 이유로 기각. 송신과 상태 기록 사이에
  프로세스 경계가 생기고, 그 경계가 정확히 이중 지급이 태어나는 자리다.
- **게이트웨이 전용 상태 테이블**: 같은 결제의 상태가 두 곳에 생긴다. 대사 대상이 하나 늘 뿐 이득이 없다.

## 결과

- `clearing-gateway` 의존성이 `common` 에서 `payment-core` 로 바뀐다.
- 게이트웨이 프로세스에도 `OutboxPoller` 가 뜬다. 의도된 것이다 — 게이트웨이가 쓴 아웃박스 이벤트는
  게이트웨이가 발행한다. 다중 인스턴스 동시 폴링은 `FOR UPDATE SKIP LOCKED` 로 이미 안전하다(ADR-0007).
- 접수 측 스케줄러(`StuckPaymentSweeper`)는 게이트웨이에서 끈다: `paycore.core.sweeper-enabled: false`.
  게이트웨이가 RECEIVED 건을 재검증하는 것은 책임 밖이다.
- Flyway 는 여전히 payment-api 만 실행한다(ADR-0004). 게이트웨이는 `flyway.enabled: false`.
- 트레이드오프: 두 프로세스가 같은 테이블에 쓴다. 서비스가 더 늘면 스키마 분리 + 소유 서비스별
  API 경계로 진화해야 하며, 그때는 이 ADR 을 대체하는 ADR 이 필요하다.
