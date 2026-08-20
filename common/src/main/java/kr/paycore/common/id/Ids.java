package kr.paycore.common.id;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Clock;
import java.time.Instant;

/**
 * 식별자 생성기 (docs §4.3).
 *
 * <p>ULID를 쓰는 이유: 26자 고정 길이, 앞 10자가 밀리초 타임스탬프라 시간순 정렬이 되고 DB 인덱스가
 * UUIDv4처럼 흩어지지 않는다. PAYMENT_ID 컬럼이 VARCHAR2(26)인 것도 여기에 맞춘 것이다.
 *
 * <p>Clock을 주입받는 이유는 테스트에서 시간을 고정하기 위함이다(CLAUDE.md 기술 규칙).
 */
public class Ids {

    /** END_TO_END_ID 접두어. ISO 20022 EndToEndId는 최대 35자 — "PC" + ULID(26) = 28자. */
    private static final String E2E_PREFIX = "PC";

    private final Clock clock;

    public Ids(Clock clock) {
        this.clock = clock;
    }

    /** 내부 PK이자 Kafka 파티션 키. */
    public String newPaymentId() {
        return ulid();
    }

    /** 전 구간 추적용 ID. 로그 correlation id이자 청산망 메시지에 실려 나간다. */
    public String newEndToEndId() {
        return E2E_PREFIX + ulid();
    }

    /** 청산 메시지 1건마다 발급되는 ID (UETR 역할). 재송신 시 새로 발급된다. */
    public String newClearingMsgId() {
        return ulid();
    }

    /** 아웃박스 이벤트 ID. */
    public String newEventId() {
        return ulid();
    }

    public Instant now() {
        return clock.instant();
    }

    private String ulid() {
        return UlidCreator.getMonotonicUlid(clock.millis()).toString();
    }
}
