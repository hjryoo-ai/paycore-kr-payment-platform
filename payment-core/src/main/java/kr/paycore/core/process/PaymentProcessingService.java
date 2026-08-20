package kr.paycore.core.process;

import java.time.Clock;
import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.DuplicateSuspectedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentRejectedEvent;
import kr.paycore.core.event.PaymentValidatedEvent;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.PaymentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 오케스트레이션 (docs §4.1, §5.2).
 *
 * <p><b>이 클래스가 outbox 패턴이 성립하는 지점이다.</b> 상태 전이 · 상태 이력 · 한도 차감 · OUTBOX_EVENT
 * INSERT 가 전부 {@code @Transactional} 하나 안에서 일어난다. 커밋되면 넷 다 있고, 롤백되면 넷 다 없다.
 * Kafka 는 이 트랜잭션에 참여하지 않으며, 발행은 전적으로 {@code OutboxPoller} 의 몫이다.
 */
@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
    private static final String TRIGGERED_BY = "core-validator";

    private final PaymentRepository payments;
    private final BusinessValidator validator;
    private final PaymentStateMachine stateMachine;
    private final OutboxWriter outbox;
    private final Clock clock;

    public PaymentProcessingService(
            PaymentRepository payments,
            BusinessValidator validator,
            PaymentStateMachine stateMachine,
            OutboxWriter outbox,
            Clock clock) {
        this.payments = payments;
        this.validator = validator;
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * 접수된 결제를 검증해 VALIDATED 또는 REJECTED 로 전이시키고, 그 사실을 아웃박스에 남긴다.
     *
     * <p>이미 RECEIVED 가 아니면 아무것도 하지 않는다 — 재기동 스위퍼나 중복 호출이 있어도 안전하도록.
     */
    @Transactional
    public void validate(String paymentId) {
        // 행을 잠그고 시작한다 — 비동기 리스너와 스위퍼가 같은 건을 동시에 집어도 한 번만 처리된다.
        Optional<Payment> found = payments.findByIdForUpdate(paymentId);
        if (found.isEmpty()) {
            log.warn("검증 대상 결제 없음 paymentId={}", paymentId);
            return;
        }
        Payment payment = found.get();
        if (payment.status() != PaymentStatus.RECEIVED) {
            log.debug("이미 검증된 건 — 건너뜀 paymentId={} status={}", paymentId, payment.status());
            return;
        }

        ValidationVerdict verdict = validator.validate(payment);

        if (!verdict.accepted()) {
            stateMachine.transition(payment, PaymentStatus.REJECTED, TRIGGERED_BY, verdict.reason());
            outbox.append(
                    payment.paymentId(),
                    PaymentEventType.PAYMENT_REJECTED,
                    new PaymentRejectedEvent(
                            payment.paymentId(),
                            payment.endToEndId(),
                            verdict.reasonCode(),
                            verdict.reason(),
                            clock.instant()));
            return;
        }

        stateMachine.transition(payment, PaymentStatus.VALIDATED, TRIGGERED_BY, null);
        outbox.append(
                payment.paymentId(),
                PaymentEventType.PAYMENT_VALIDATED,
                new PaymentValidatedEvent(
                        payment.paymentId(),
                        payment.endToEndId(),
                        payment.debtorAccount(),
                        payment.creditorAccount(),
                        payment.creditorBank(),
                        payment.amount(),
                        payment.currency(),
                        payment.remittanceInfo(),
                        clock.instant()));

        if (verdict.duplicateSuspected()) {
            // 차단하지 않는다. 운영자가 볼 수 있도록 사실만 남긴다 (docs §5.2).
            outbox.append(
                    payment.paymentId(),
                    PaymentEventType.DUPLICATE_SUSPECTED,
                    new DuplicateSuspectedEvent(
                            payment.paymentId(),
                            payment.endToEndId(),
                            verdict.duplicateOfPaymentId(),
                            payment.amount(),
                            clock.instant()));
            log.warn("중복 의심 paymentId={} 이전건={}", payment.paymentId(), verdict.duplicateOfPaymentId());
        }
    }
}
