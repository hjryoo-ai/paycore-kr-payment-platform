package kr.paycore.core.event;

import java.time.Instant;

/**
 * 청산망이 거절(RJCT)했거나, 상태조회로 <b>미처리가 확정</b>됐다. 돈은 나가지 않았다.
 *
 * <p>{@code resendPermitted} 는 §7.3 의 정책 판단 결과다 — 청산망이 "원거래를 받은 적 없다(NOOR)"고
 * 답한 경우에만 true 다. 이 플래그가 false 인데 재송신하면 그게 이중 지급이다.
 */
public record PaymentFailedEvent(
        String paymentId,
        String endToEndId,
        String clearingMsgId,
        String reasonCode,
        String reason,
        boolean confirmedByInquiry,
        boolean resendPermitted,
        Instant occurredAt) {}
