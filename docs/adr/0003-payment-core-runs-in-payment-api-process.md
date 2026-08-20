# ADR-0003: payment-core는 별도 프로세스가 아니라 payment-api와 같은 JVM에서 구동한다

- 상태: 채택
- 날짜: 2026-08-19
- 관련: 설계 문서 §3.1(모듈 목록), §4.1(시퀀스 다이어그램)

## 맥락
설계 문서 §3.1은 `payment-api` 와 `payment-core` 를 각각 "[서비스]" 로 표기한다. 반면 §4.1의
happy path 시퀀스 다이어그램은 `A->>CO: (같은 프로세스) validate 요청` 이라고 명시한다. 문서 안에서
두 표현이 엇갈린다.

## 결정
- `payment-core` 는 **`java-library` 모듈**(부트 실행 파일 아님)로 만든다: 도메인 모델, `PaymentStateMachine`,
  비즈니스 검증, Outbox 저장/발행기, Kafka consumer 를 담는다.
- `payment-api` 가 **유일한 실행 가능 아티팩트**로 `payment-core` 를 패키징한다. 접수 API 호출은
  같은 JVM 안의 메서드 호출이고, 상태 변경 + OUTBOX insert 는 **하나의 로컬 트랜잭션**으로 묶인다.
- 모듈 경계는 Gradle 의존 방향으로 강제한다: `payment-api → payment-core → common`. 역방향 금지.

## 기각한 대안
- **완전히 분리된 두 서비스**: API→core 호출이 네트워크를 건너면 §7.1 outbox의 전제(상태 변경과 이벤트
  기록이 같은 로컬 트랜잭션)를 지키기 위해 core 쪽에 또 하나의 접수 저장소가 필요해진다. 포트폴리오가
  증명하려는 정합성 주제와 무관한 복잡도만 늘어난다. §4.1이 "같은 프로세스"라고 못 박은 이유이기도 하다.
- **하나의 모듈로 합치기**: 채널 관심사(HTTP, 멱등 응답 재생)와 도메인 관심사(상태머신, outbox)가 섞인다.
  모듈을 나눠 두면 나중에 프로세스 분리가 필요해졌을 때 경계가 이미 그어져 있다.

## 결과
- 배포 단위는 5개: `payment-api`(+core), `clearing-gateway`, `clearing-simulator`, `ledger-service`, `recon-batch`.
- 서비스 간 통신은 전부 비동기(Kafka/JMS)라는 설계 §3.2 원칙이 오히려 더 깨끗하게 지켜진다.
- 설계 문서 §3.1의 `[서비스]` 표기는 `payment-core` 에 한해 `[라이브러리]` 로 읽는다.
