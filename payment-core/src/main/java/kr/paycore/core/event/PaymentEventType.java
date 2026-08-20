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
    /** 청산 응답이 확정된 상태와 모순된다 (docs §7.4). 상태를 덮어쓰지 않고 사실만 알린다. */
    public static final String CLEARING_CONTRADICTION = "ClearingContradictionDetected";

    private PaymentEventType() {}
}
