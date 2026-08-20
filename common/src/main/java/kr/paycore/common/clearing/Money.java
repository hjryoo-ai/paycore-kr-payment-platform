package kr.paycore.common.clearing;

/**
 * 청산 메시지의 금액 필드.
 *
 * <p>{@code value} 가 {@code long} 인 것은 타협 대상이 아니다 — 원화는 최소 단위가 1원인 정수 통화이고,
 * 부동소수점은 금액에 쓰지 않는다(CLAUDE.md). JSON 스키마에서도 {@code integer} 로 강제한다.
 */
public record Money(String ccy, long value) {

    public static final String KRW = "KRW";

    public static Money krw(long value) {
        return new Money(KRW, value);
    }
}
