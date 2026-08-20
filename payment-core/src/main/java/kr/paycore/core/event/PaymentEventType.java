package kr.paycore.core.event;

/** 내부 이벤트 타입 (Kafka {@code payment.events} 토픽). 이름은 '사실'을 과거형으로 적는다. */
public final class PaymentEventType {

    public static final String PAYMENT_VALIDATED = "PaymentValidated";
    public static final String PAYMENT_REJECTED = "PaymentRejected";
    public static final String DUPLICATE_SUSPECTED = "DuplicateSuspected";
    public static final String PAYMENT_CLEARED = "PaymentCleared";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_UNKNOWN = "PaymentUnknown";
    public static final String PAYMENT_SETTLED = "PaymentSettled";
    public static final String PAYMENT_MANUAL_REVIEW = "PaymentManualReview";

    private PaymentEventType() {}
}
