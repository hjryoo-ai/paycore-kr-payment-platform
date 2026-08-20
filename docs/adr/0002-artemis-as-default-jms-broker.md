# ADR-0002: 외부 청산망 JMS 브로커는 ActiveMQ Artemis를 기본으로, IBM MQ는 선택 프로파일로 둔다

- 상태: 채택
- 날짜: 2026-08-19
- 관련: 설계 문서 §2 (Messaging 외부), §5.3, §5.4

## 맥락
설계 문서 §2는 외부 청산망 인터페이스로 IBM MQ Developer Edition(`icr.io/ibm-messaging/mq`)을
우선 선택하고, "무겁다면 ActiveMQ Artemis(JMS)로 대체 가능 — 코드에서는 JMS 추상화 유지"를 허용했다.

개발 환경은 Apple Silicon(arm64) macOS이다. 2026-08-19 기준 레지스트리 매니페스트를 직접 조회한 결과:

```
icr.io/ibm-messaging/mq:latest → linux/amd64, linux/s390x, linux/ppc64le   (arm64 없음)
apache/activemq-artemis:2.44.0 → linux/amd64, linux/arm64
```

즉 IBM MQ는 arm64 네이티브 이미지가 없어 QEMU 에뮬레이션으로만 구동된다. 기동에 수 분이 걸리고,
Testcontainers 기반 통합 테스트(설계 §9의 핵심 검증 수단)가 느려지고 간헐적으로 실패한다.
반면 이 프로젝트가 증명하려는 것은 "IBM MQ 제품 지식"이 아니라 **request/response 큐 + correlation id
기반 외부 연동과 그 위의 정합성 처리**다.

## 결정
1. 애플리케이션 코드는 **`jakarta.jms` 표준 API + Spring `JmsTemplate`/`@JmsListener`** 만 사용한다.
   벤더 고유 API(`MQQueue`, `MQConnectionFactory` 등)는 `@Profile("ibmmq")` 로 격리된
   `ConnectionFactory` 빈 정의 한 곳에만 등장한다.
2. `docker-compose.yml` 의 **기본 브로커는 `apache/activemq-artemis:2.44.0`** (arm64 네이티브).
   Testcontainers 통합 테스트도 Artemis를 사용한다.
3. IBM MQ는 `--profile ibmmq` 로 기동하는 **선택 서비스**로 유지한다. `com.ibm.mq:com.ibm.mq.jakarta.client`
   의존성과 `ibmmq` 스프링 프로파일 설정을 저장소에 포함해, amd64 환경(또는 에뮬레이션 감수)에서는
   설정 변경만으로 IBM MQ에 붙는다.

## 기각한 대안
- **IBM MQ 고정**: 에뮬레이션 비용이 통합 테스트 전략 전체를 훼손한다. 얻는 것은 문구뿐이다.
- **Artemis 전용, IBM MQ 흔적 삭제**: JMS 추상화가 실제로 벤더 교체 가능한지 증명할 수 없게 된다.
  "추상화했다"는 주장보다 "두 벤더 설정이 실제로 저장소에 있다"가 강하다.
- **Kafka로 외부 연동까지 통일**: 설계 의도(내부 이벤트 vs 외부 request/response 인터페이스의 역할 분리)를
  잃는다. 실제 은행-공동망 연동 형태와 멀어진다.

## 결과
- 통합 테스트가 arm64 네이티브로 빠르게 돌아 §8 장애 시나리오 자동화가 실용적으로 유지된다.
- "MQ + Kafka 병행" 이라는 채용 요건은 JMS(Artemis/IBM MQ) + Kafka 구성으로 그대로 충족된다.
- 클라이언트/브로커 버전 차 검증: Boot 4.1 BOM이 관리하는 Artemis 클라이언트는 2.53.0이고 공식 브로커
  이미지의 최신 태그는 2.44.0이다. 2026-08-19 로컬에서 실제 send/receive 를 수행해 호환을 확인했다
  (`client=2.53.0 received=hello-pacs008`). 향후 브로커를 올릴 때 재확인한다.
