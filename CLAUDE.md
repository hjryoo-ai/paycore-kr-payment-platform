# CLAUDE.md — PayCore-KR 작업 규칙

## 프로젝트
`docs/payment-platform-design.md` 가 단일 진실(SSOT)이다.
설계와 다른 구현이 필요하면 먼저 `docs/adr/` 에 ADR 초안을 쓰고 사용자 확인을 받는다.

## 기술 규칙
- Java 21, Spring Boot 4.1.x — 모든 버전은 `gradle/libs.versions.toml` 에 고정한다.
- **버전/설정값을 추측하지 말 것.** 모르면 Maven Central / 레지스트리 매니페스트를 실제로 조회하거나 사용자에게 질문.
- 금액은 `long`(KRW 정수). `double`/`float`/`BigDecimal 소수` 금지.
- 모든 시간 로직은 `java.time` + **주입된 `Clock`** 사용. `Instant.now()` 직접 호출 금지.
- DB 접근은 Spring Data JPA + 필요한 곳만 native query. 스키마 변경은 Flyway 마이그레이션으로만.
- 메시지 스키마(pacs.*)는 `common/src/main/resources/schemas/` 의 JSON Schema가 원본.
- 로그에 계좌번호/개인정보 원문 금지 — `AccountMasker` 경유.

## 정합성 불변식 (테스트로 강제)
1. 하나의 결제로 돈이 두 번 나가지 않는다 (`END_TO_END_ID` 유일성 + 시뮬레이터 DUPL 거절).
2. 상태 전이는 `PaymentStateMachine` 의 허용 표를 벗어날 수 없다.
3. DB 상태 변경과 이벤트 발행은 **outbox 로만** 연결된다 (payment-core 내 `KafkaTemplate` 직접 호출 금지).
4. 모든 consumer 는 `PROCESSED_MESSAGE` dedup 을 거친다.
5. timeout ≠ 실패. timeout 은 `UNKNOWN` 이며 blind resend 는 금지된다.

## 작업 방식
- Phase 단위로 작업(`docs/payment-platform-design.md` §12.2). 각 Phase 의 DoD 를 전부 만족해야 다음으로.
- 테스트 없는 기능 코드 커밋 금지. 통합 테스트는 Testcontainers.
- 커밋 메시지: conventional commits (feat/fix/test/docs/chore).
- **실패한 테스트를 통과시키기 위해 테스트를 약화시키지 말 것.**
- 통합 테스트에서 `Thread.sleep` 금지 — Awaitility 사용.

## 자주 쓰는 명령
```bash
./gradlew build                      # 전체 빌드 + 테스트
./gradlew spotlessApply              # 포맷
./gradlew bootJar                    # 실행 가능 jar
docker compose up -d oracle kafka artemis   # 인프라만
scripts/build-images.sh && docker compose up -d   # 전체 스택
```
