package kr.paycore.api.ops;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentFailedEvent;
import kr.paycore.core.observability.PaymentMdc;
import kr.paycore.core.ops.DeadLetter;
import kr.paycore.core.ops.DeadLetterRepository;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.IllegalStateTransitionException;
import kr.paycore.core.statemachine.PaymentStateMachine;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 개입 (docs §5.7, §7.5).
 *
 * <p>두 가지만 한다: 자동으로 결론 내지 못한 결제를 사람이 확정하는 것, 그리고 DLT 로 밀려난
 * 메시지를 사람이 확인한 뒤 다시 흘려보내는 것. 둘 다 <b>반드시 감사 기록과 같은 트랜잭션</b>이다.
 */
@Service
public class OpsService {

    public static final String ACTION_REPAIR = "REPAIR_PAYMENT";
    public static final String ACTION_REPUBLISH = "REPUBLISH_DEAD_LETTER";
    public static final String ACTION_DISCARD = "DISCARD_DEAD_LETTER";

    private static final int WORKLIST_LIMIT = 200;

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final OutboxWriter outbox;
    private final DeadLetterRepository deadLetters;
    private final OpsAuditService audit;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String eventsTopic;
    private final Clock clock;

    public OpsService(
            PaymentRepository payments,
            PaymentStateMachine stateMachine,
            OutboxWriter outbox,
            DeadLetterRepository deadLetters,
            OpsAuditService audit,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${paycore.core.events-topic:payment.events}") String eventsTopic,
            Clock clock) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.deadLetters = deadLetters;
        this.audit = audit;
        this.kafkaTemplate = kafkaTemplate;
        this.eventsTopic = eventsTopic;
        this.clock = clock;
    }

    /** 사람이 봐야 하는 결제들. 자동 처리가 손을 뗀 지점이다. */
    public List<Payment> worklist(PaymentStatus status) {
        return payments.findByStatus(status, PageRequest.of(0, WORKLIST_LIMIT));
    }

    /**
     * 운영자가 확인한 사실로 결제를 확정한다.
     *
     * <p>전이 규칙은 우회하지 않는다. {@code MANUAL_REVIEW} 가 아닌 건을 손으로 바꾸려 하면
     * 상태머신이 막는다 — repair 는 예외 통로가 아니라 <b>표에 이미 있는 길</b>이다.
     */
    @Transactional
    public Payment repair(String paymentId, RepairRequest request, String actor) {
        Payment payment = payments.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotRepairableException(paymentId, "결제를 찾을 수 없습니다."));

        try (PaymentMdc.Scope scope = PaymentMdc.with(payment.paymentId(), payment.endToEndId())) {
            return repairLocked(payment, request, actor);
        }
    }

    private Payment repairLocked(Payment payment, RepairRequest request, String actor) {
        String paymentId = payment.paymentId();
        PaymentStatus target =
                request.decision() == RepairDecision.CLEARED ? PaymentStatus.CLEARED : PaymentStatus.FAILED;
        PaymentStatus before = payment.status();

        boolean changed;
        try {
            changed = stateMachine.transition(payment, target, "operator:" + actor, request.reason());
        } catch (IllegalStateTransitionException e) {
            throw new PaymentNotRepairableException(paymentId, before + " 상태는 운영자가 " + target + " 로 바꿀 수 없습니다.");
        }
        if (!changed) {
            throw new PaymentNotRepairableException(paymentId, "이미 " + target + " 입니다.");
        }

        // 감사 기록이 상태 변경과 같은 커밋에 묶인다. 하나만 남는 경우를 만들지 않는다.
        audit.record(
                actor,
                ACTION_REPAIR,
                OpsAuditService.TARGET_PAYMENT,
                paymentId,
                before + " → " + target + " : " + request.reason());

        emit(payment, target, actor, request.reason());
        return payment;
    }

    /**
     * DLT 메시지를 원본 토픽으로 되돌린다. <b>자동으로는 절대 하지 않는다</b> (docs §7.5).
     *
     * <p>소비자들은 모두 inbox dedup 을 거치므로, 이미 처리된 메시지가 다시 들어와도 두 번
     * 처리되지 않는다. 재발행이 안전한 이유가 "운영자가 조심해서"가 아니라 구조라는 점이 중요하다.
     */
    @Transactional
    public DeadLetter republish(String deadLetterId, String actor, String reason) {
        DeadLetter entry = deadLetters
                .findById(deadLetterId)
                .orElseThrow(() -> new DeadLetterNotActionableException(deadLetterId, "DLT 항목을 찾을 수 없습니다."));
        if (!entry.isOpen()) {
            throw new DeadLetterNotActionableException(deadLetterId, "이미 " + entry.status() + " 처리된 항목입니다.");
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(eventsTopic, entry.messageKey(), entry.payload());
        if (entry.eventId() != null) {
            record.headers().add(new RecordHeader("eventId", entry.eventId().getBytes()));
        }
        if (entry.eventType() != null) {
            record.headers().add(new RecordHeader("eventType", entry.eventType().getBytes()));
        }
        record.headers().add(new RecordHeader("republishedBy", actor.getBytes()));
        kafkaTemplate.send(record);

        entry.markRepublished(clock.instant());
        audit.record(
                actor,
                ACTION_REPUBLISH,
                OpsAuditService.TARGET_DEAD_LETTER,
                deadLetterId,
                "eventType=" + entry.eventType() + " : " + reason);
        log.warn("DLT 재발행 deadLetterId={} actor={} eventType={}", deadLetterId, actor, entry.eventType());
        return entry;
    }

    /** 재처리하지 않기로 한 항목. 지우지 않고 상태만 바꾼다 — 판단한 사실도 기록이다. */
    @Transactional
    public DeadLetter discard(String deadLetterId, String actor, String reason) {
        DeadLetter entry = deadLetters
                .findById(deadLetterId)
                .orElseThrow(() -> new DeadLetterNotActionableException(deadLetterId, "DLT 항목을 찾을 수 없습니다."));
        if (!entry.isOpen()) {
            throw new DeadLetterNotActionableException(deadLetterId, "이미 " + entry.status() + " 처리된 항목입니다.");
        }
        entry.markDiscarded(clock.instant());
        audit.record(actor, ACTION_DISCARD, OpsAuditService.TARGET_DEAD_LETTER, deadLetterId, reason);
        return entry;
    }

    private void emit(Payment payment, PaymentStatus target, String actor, String reason) {
        Optional<String> none = Optional.empty();
        if (target == PaymentStatus.CLEARED) {
            outbox.append(
                    payment.paymentId(),
                    PaymentEventType.PAYMENT_CLEARED,
                    new PaymentClearedEvent(
                            payment.paymentId(),
                            payment.endToEndId(),
                            none.orElse(null),
                            payment.amount(),
                            payment.currency(),
                            payment.debtorAccount(),
                            payment.creditorAccount(),
                            false,
                            clock.instant()));
        } else {
            outbox.append(
                    payment.paymentId(),
                    PaymentEventType.PAYMENT_FAILED,
                    new PaymentFailedEvent(
                            payment.paymentId(),
                            payment.endToEndId(),
                            none.orElse(null),
                            "OPERATOR",
                            "운영자 확정(" + actor + "): " + reason,
                            false,
                            false,
                            clock.instant()));
        }
    }
}
