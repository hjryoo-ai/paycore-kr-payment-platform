package kr.paycore.recon.match;

import kr.paycore.core.domain.PaymentStatus;

/** 대사 시점의 <b>"우리가 아는 것"</b> (docs §5.6). */
public record PaymentSnapshot(
        String paymentId, String endToEndId, PaymentStatus status, long amount, String debtorAccount) {

    /** 우리가 '돈이 나갔다'고 믿는 상태인가. */
    public boolean believesPaid() {
        return status == PaymentStatus.CLEARED || status == PaymentStatus.SETTLED;
    }

    /** 우리가 '돈이 나가지 않았다'고 확정한 상태인가. */
    public boolean believesNotPaid() {
        return status == PaymentStatus.REJECTED || status == PaymentStatus.FAILED;
    }

    /** 아직 결론을 내지 못한 상태인가. 방치된 UNKNOWN 이 여기에 해당한다. */
    public boolean undecided() {
        return !believesPaid() && !believesNotPaid();
    }
}
