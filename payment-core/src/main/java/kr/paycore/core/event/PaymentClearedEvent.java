package kr.paycore.core.event;

import java.time.Instant;

/**
 * 청산망이 결제 완료(ACSC)를 확인했다 — <b>돈이 나갔다는 사실</b>이다.
 *
 * <p>{@code confirmedByInquiry} 는 이 확인이 원 응답(pacs.002)에서 왔는지, timeout 후
 * 상태조회(pacs.028)에서 왔는지를 남긴다. 대사와 사후 분석에서 "우리가 어떻게 알게 됐는가"는
 * "무엇을 아는가"만큼 중요하다.
 */
public record PaymentClearedEvent(
        String paymentId,
        String endToEndId,
        String clearingMsgId,
        long amount,
        String currency,
        String debtorAccount,
        String creditorAccount,
        boolean confirmedByInquiry,
        Instant occurredAt) {}
