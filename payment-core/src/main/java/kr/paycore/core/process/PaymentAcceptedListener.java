package kr.paycore.core.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수 커밋 이후 검증을 비동기로 시작한다 (docs §4.1 — 202 응답이 먼저, 검증이 나중).
 *
 * <p>여기서 실패해도 결제가 사라지지 않는다: 상태는 RECEIVED 로 남고 {@link StuckPaymentSweeper} 가
 * 다시 집어 간다. 비동기 처리에서 "실패하면 아무도 모른다"가 되지 않도록 만든 안전망이다.
 */
@Component
public class PaymentAcceptedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentAcceptedListener.class);

    private final PaymentProcessingService processing;

    public PaymentAcceptedListener(PaymentProcessingService processing) {
        this.processing = processing;
    }

    @Async("paycoreTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccepted(PaymentAcceptedEvent event) {
        try {
            processing.validate(event.paymentId());
        } catch (RuntimeException e) {
            log.error("접수 직후 검증 실패 — RECEIVED 로 남겨 스위퍼가 재처리한다 paymentId={}", event.paymentId(), e);
        }
    }
}
