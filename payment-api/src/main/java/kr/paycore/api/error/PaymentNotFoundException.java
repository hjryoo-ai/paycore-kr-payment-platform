package kr.paycore.api.error;

/** 조회 대상 결제 건이 없을 때. */
public class PaymentNotFoundException extends RuntimeException {

    private final String paymentId;

    public PaymentNotFoundException(String paymentId) {
        super("결제 건을 찾을 수 없습니다.");
        this.paymentId = paymentId;
    }

    public String paymentId() {
        return paymentId;
    }
}
