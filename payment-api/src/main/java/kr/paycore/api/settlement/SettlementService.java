package kr.paycore.api.settlement;

import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentSettledEvent;
import kr.paycore.core.inbox.InboxGuard;
import kr.paycore.core.statemachine.IllegalStateTransitionException;
import kr.paycore.core.statemachine.PaymentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원장 반영 완료를 상태에 반영한다 ({@code CLEARED → SETTLED}, docs §4.1).
 *
 * <p>상태의 소유자는 payment-core 이고, 그 core 를 품고 있는 것이 이 프로세스다(ADR-0003).
 * 원장 서비스가 직접 결제 상태를 바꾸지 않는 이유는 책임 분리다 — 원장은 기록하고, 상태는 core 가 정한다.
 */
@Service
public class SettlementService {

    private static final String TRIGGERED_BY = "ledger-settlement";

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final InboxGuard inbox;
    private final String consumerGroup;

    public SettlementService(
            PaymentRepository payments,
            PaymentStateMachine stateMachine,
            InboxGuard inbox,
            @Value("${paycore.api.consumer-group:payment-api}") String consumerGroup) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.inbox = inbox;
        this.consumerGroup = consumerGroup;
    }

    @Transactional
    public boolean settle(String eventId, PaymentSettledEvent event) {
        if (!inbox.claim(consumerGroup, eventId)) {
            return false;
        }

        Optional<Payment> found = payments.findByIdForUpdate(event.paymentId());
        if (found.isEmpty()) {
            log.error("정산 이벤트가 가리키는 결제가 없다 paymentId={} eventId={}", event.paymentId(), eventId);
            return false;
        }
        Payment payment = found.get();
        if (payment.status() == PaymentStatus.SETTLED) {
            return false;
        }

        try {
            return stateMachine.transition(payment, PaymentStatus.SETTLED, TRIGGERED_BY, "원장 반영 완료");
        } catch (IllegalStateTransitionException e) {
            // CLEARED 가 아닌 상태에서 정산 이벤트가 온 것 자체가 모순이다. 덮어쓰지 않고 사실만 남긴다(docs §7.4).
            log.error(
                    "정산 이벤트와 현재 상태가 모순된다 — 자동으로 덮어쓰지 않는다 paymentId={} 현재={} journalId={}",
                    payment.paymentId(),
                    payment.status(),
                    event.journalId());
            return false;
        }
    }
}
