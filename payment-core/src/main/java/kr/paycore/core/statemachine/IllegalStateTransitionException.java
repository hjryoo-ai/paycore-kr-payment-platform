package kr.paycore.core.statemachine;

import kr.paycore.core.domain.PaymentStatus;

/**
 * 전이표에 없는 상태 전이 시도. <b>삼켜서는 안 되는 예외</b>다 — 여기까지 왔다는 것은 어딘가에서
 * 상태에 대한 가정이 깨졌다는 뜻이고, 결제 시스템에서 그것은 곧 돈의 문제다.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final String paymentId;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public IllegalStateTransitionException(String paymentId, PaymentStatus from, PaymentStatus to) {
        super("허용되지 않은 상태 전이: %s %s -> %s".formatted(paymentId, from, to));
        this.paymentId = paymentId;
        this.from = from;
        this.to = to;
    }

    public String paymentId() {
        return paymentId;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}
