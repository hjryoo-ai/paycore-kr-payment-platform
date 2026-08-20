# ADR-0004: 단일 Oracle 스키마를 쓰고, Flyway 마이그레이션은 payment-api만 실행한다

- 상태: 채택
- 날짜: 2026-08-19
- 관련: 설계 문서 §6(데이터 모델)

## 맥락
설계 문서 §6은 `PAYMENT`, `OUTBOX_EVENT`, `CLEARING_MESSAGE_LOG`, `JOURNAL`, `LEDGER_ENTRY`,
`RECON_BREAK` 를 하나의 스키마에 정의한다. 이 테이블들을 서로 다른 서비스(payment-api/core,
clearing-gateway, ledger-service, recon-batch)가 사용한다. 모든 서비스가 각자 Flyway를 실행하면
같은 스키마에 동시 마이그레이션이 걸리고, 어떤 서비스가 스키마의 주인인지 모호해진다.

## 결정
- 마이그레이션 스크립트는 `payment-core/src/main/resources/db/migration/` **한 곳**에만 둔다.
- `spring.flyway.enabled=true` 는 **`payment-api` 에서만**. 나머지 서비스는 `false`.
- `docker-compose.yml` 에서 다른 서비스들은 `payment-api: {condition: service_healthy}` 를 기다린다.
- 서비스별 테이블 소유권은 문서로 명시한다(아래). 소유 서비스가 아닌 곳에서의 쓰기는 리뷰 대상.

| 테이블 | 쓰기 소유 |
|---|---|
| `PAYMENT`, `PAYMENT_STATUS_HISTORY`, `OUTBOX_EVENT` | payment-core (payment-api 프로세스) |
| `CLEARING_MESSAGE_LOG` | clearing-gateway |
| `JOURNAL`, `LEDGER_ENTRY` | ledger-service |
| `RECON_BREAK` | recon-batch |
| `PROCESSED_MESSAGE` | 모든 consumer (consumer_group 으로 분리) |

## 기각한 대안
- **서비스별 독립 스키마 + 서비스별 Flyway**: 실무에서는 옳지만, 이 프로젝트는 §6에서 이미 3-way 대사를
  단일 스키마 조인으로 전제한다. 스키마를 쪼개면 대사 배치가 서비스 간 API 호출/데이터 복제로 번지고,
  증명하려는 주제(정합성)가 아니라 분산 데이터 관리 문제로 초점이 옮겨간다.
- **전용 마이그레이션 컨테이너**: 운영에서는 가장 깨끗하지만 모듈이 하나 더 늘고, 로컬 기동 절차가
  복잡해진다. 스키마가 더 커지면 이 방향으로 옮긴다.

## 결과
- 기동 순서 의존성이 생긴다(payment-api → 나머지). compose `depends_on` 과 README에 명시했다.
- 한계와 진화 경로("서비스가 늘면 스키마 분리 + 마이그레이션 컨테이너")를 README 단순화 선언에 포함한다.
