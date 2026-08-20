package kr.paycore.core.event;

import java.time.Instant;

/**
 * 자동으로 결론 낼 수 없다 — 운영자가 봐야 한다 (docs §7.3 마지막 갈래).
 *
 * <p>여기까지 온 건은 <b>추측으로 상태를 확정하지 않는다</b>. 대시보드 워크리스트에 올라가고
 * 사람이 청산망과 확인한 뒤 repair API 로 CLEARED 또는 FAILED 를 지정한다(Phase 6).
 */
public record PaymentManualReviewEvent(
        String paymentId, String endToEndId, String reason, int inquiryAttempts, Instant occurredAt) {}
