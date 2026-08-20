package kr.paycore.api.ops;

/** 운영자가 이 결제를 그렇게 바꿀 수 없다. 상태머신이 막았거나 대상이 없다. */
public class PaymentNotRepairableException extends RuntimeException {

    private final String paymentId;

    public PaymentNotRepairableException(String paymentId, String message) {
        super(message);
        this.paymentId = paymentId;
    }

    public String paymentId() {
        return paymentId;
    }
}
