package kr.paycore.core.event;

import java.time.Instant;

/**
 * 원장 반영이 끝났다 (docs §4.1 마지막 단계).
 *
 * <p>이 이벤트를 받은 payment-core 가 {@code CLEARED → SETTLED} 로 전이시킨다. 원장이 상태를 직접
 * 바꾸지 않는 이유는, 원장은 '기록하는 쪽'이지 '결제 상태를 소유하는 쪽'이 아니기 때문이다.
 */
public record PaymentSettledEvent(
        String paymentId, String endToEndId, String journalId, long amount, String currency, Instant occurredAt) {}
