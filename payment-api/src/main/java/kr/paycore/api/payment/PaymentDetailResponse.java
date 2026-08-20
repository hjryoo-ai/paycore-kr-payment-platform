package kr.paycore.api.payment;

import java.time.Instant;
import java.util.List;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatusHistory;

/**
 * 결제 상세 + 상태 타임라인 (docs §5.1).
 *
 * <p>계좌번호는 응답에서도 마스킹한다. 이 API 의 주 소비자는 운영 대시보드이고, 운영 화면에 원본 계좌번호가
 * 필요한 업무는 없다. 원본이 필요하면 별도 권한 API 를 두는 것이 옳다.
 */
public record PaymentDetailResponse(
        String paymentId,
        String endToEndId,
        String status,
        String debtorAccount,
        String creditorAccount,
        String creditorBankCode,
        long amount,
        String currency,
        String remittanceInfo,
        Instant createdAt,
        Instant updatedAt,
        List<Timeline> history) {

    public record Timeline(String from, String to, String triggeredBy, String reason, Instant at) {}

    public static PaymentDetailResponse of(Payment p, List<PaymentStatusHistory> history) {
        return new PaymentDetailResponse(
                p.paymentId(),
                p.endToEndId(),
                p.status().name(),
                AccountMasker.mask(p.debtorAccount()),
                AccountMasker.mask(p.creditorAccount()),
                p.creditorBank(),
                p.amount(),
                p.currency(),
                p.remittanceInfo(),
                p.createdAt(),
                p.updatedAt(),
                history.stream()
                        .map(h -> new Timeline(
                                h.fromStatus() == null ? null : h.fromStatus().name(),
                                h.toStatus().name(),
                                h.triggeredBy(),
                                h.reason(),
                                h.createdAt()))
                        .toList());
    }
}
