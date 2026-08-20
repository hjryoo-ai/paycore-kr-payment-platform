package kr.paycore.core.event;

import java.time.Instant;

/**
 * 청산망 응답이 오지 않았다. <b>실패가 아니라 '모른다'</b>이다 (docs §7.3 절대 규칙).
 *
 * <p>이 이벤트를 받고 재송신하는 소비자를 만들지 말 것. 확인 수단은 pacs.028 inquiry 뿐이다.
 */
public record PaymentUnknownEvent(
        String paymentId, String endToEndId, String clearingMsgId, long amount, Instant sentAt, Instant occurredAt) {}
