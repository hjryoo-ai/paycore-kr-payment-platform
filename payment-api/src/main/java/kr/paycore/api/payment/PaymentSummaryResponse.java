package kr.paycore.api.payment;

import java.time.Instant;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.core.domain.Payment;

/** 목록 조회용 요약 (docs §5.1 운영 조회). */
public record PaymentSummaryResponse(
        String paymentId,
        String endToEndId,
        String status,
        String debtorAccount,
        String creditorAccount,
        String creditorBankCode,
        long amount,
        String currency,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentSummaryResponse of(Payment p) {
        return new PaymentSummaryResponse(
                p.paymentId(),
                p.endToEndId(),
                p.status().name(),
                AccountMasker.mask(p.debtorAccount()),
                AccountMasker.mask(p.creditorAccount()),
                p.creditorBank(),
                p.amount(),
                p.currency(),
                p.createdAt(),
                p.updatedAt());
    }
}
