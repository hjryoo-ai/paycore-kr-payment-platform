# ADR-0005: 시각 컬럼은 TIMESTAMP WITH TIME ZONE 을 쓴다

- 상태: 채택
- 날짜: 2026-08-19
- 관련: 설계 문서 §6(데이터 모델)

## 맥락
설계 문서 §6의 DDL은 시각 컬럼을 `TIMESTAMP` 로 적었고, 엔티티는 `java.time.Instant` 로 매핑했다.
Phase 1 통합 테스트에서 INSERT 는 성공하는데 SELECT 가 전부 500 으로 실패했다:

```
ORA-18716: {0} not in any time zone
JpaSystemException: Could not extract column [3] from JDBC ResultSet
```

원인은 추측이 아니라 확인된 것이다. Hibernate 6 이후 `Instant` 는 SQL 타입 `TIMESTAMP_UTC` 로 매핑되고,
Oracle 방언에서 이는 `TIMESTAMP WITH TIME ZONE` 이다. 그래서 읽을 때 `ResultSet.getObject(n, OffsetDateTime.class)`
를 호출하는데, 컬럼이 오프셋 없는 `TIMESTAMP` 라 드라이버가 거부한다.

## 결정
`PAYMENT`, `PAYMENT_STATUS_HISTORY`, `OUTBOX_EVENT`, `PROCESSED_MESSAGE`, `CLEARING_MESSAGE_LOG`,
`JOURNAL`, `RECON_BREAK` 의 **시각 컬럼을 `TIMESTAMP WITH TIME ZONE` 으로** 정의한다.
`RECON_BREAK.RECON_DATE` 는 시점이 아니라 **업무일자**이므로 `DATE` 를 유지한다 — 이 구분 자체가 의미가 있다.

`spring.jpa.properties.hibernate.jdbc.time_zone` 설정은 제거했다. 컬럼이 오프셋을 스스로 갖게 되면
이 설정은 불필요하고, 남겨두면 "어느 존으로 저장되는가"에 대한 두 번째 진실이 생겨 혼란만 만든다.

## 기각한 대안
- **`@JdbcTypeCode(SqlTypes.TIMESTAMP)` 로 엔티티를 컬럼에 맞추기**: DDL 은 §6 그대로 유지되지만,
  컬럼에 오프셋 정보가 없어 "이 값은 UTC 다"라는 약속이 코드 밖에서는 보이지 않는다. DB 를 직접 조회하는
  운영자·대사 배치·리포트가 각자 이 약속을 알아야 하고, 한 곳이라도 KST 로 해석하면 9시간이 어긋난다.
  결제 시스템에서 시각 해석 오류는 마감·시효·중복판정을 동시에 망가뜨린다.
- **`LocalDateTime` 으로 매핑**: 위와 같은 문제에 더해, 애플리케이션 안에서도 존이 사라진다.

## 결과
- 컬럼이 자기 오프셋을 설명하므로 `SYSTIMESTAMP` 기본값, Hibernate `Instant` 매핑, 직접 SQL 조회가 모두
  같은 값을 본다.
- V1 마이그레이션을 수정했다. 아직 어디에도 배포되지 않은 초기 스키마이기 때문이며,
  **Phase 2 이후의 마이그레이션은 append-only** 로 관리한다.
- 로컬 스택은 스키마를 다시 만들어야 한다: `docker compose down -v && docker compose up -d`.
