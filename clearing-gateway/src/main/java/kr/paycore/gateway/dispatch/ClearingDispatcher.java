package kr.paycore.gateway.dispatch;

import java.time.Clock;
import java.util.Optional;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.common.clearing.Money;
import kr.paycore.common.clearing.Pacs008;
import kr.paycore.common.id.Ids;
import kr.paycore.core.clearing.ClearingMessageLog;
import kr.paycore.core.clearing.ClearingMessageLogRepository;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentValidatedEvent;
import kr.paycore.core.inbox.InboxGuard;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pacs.008 송신 준비 (docs §5.3, ADR-0008).
 *
 * <p><b>트랜잭션 하나에 네 가지가 묶인다</b>: inbox 선점 · 상태 전이 {@code VALIDATED → SENT_TO_CLEARING} ·
 * 상태 이력 · {@code CLEARING_MESSAGE_LOG} 기록. 커밋되면 넷 다 있고, 롤백되면 넷 다 없다.
 * 실제 송신은 이 메서드가 <b>끝난 뒤</b> 호출자가 한다.
 *
 * <p>커밋 후 송신 전에 죽으면? 그 건은 SENT_TO_CLEARING 으로 남고 timeout → UNKNOWN → pacs.028 →
 * 청산망이 "받은 적 없음(NOOR)" → FAILED 로 수렴한다. blind resend 없이 정확히 끝난다(docs §7.3).
 */
@Service
public class ClearingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClearingDispatcher.class);

    private final PaymentRepository payments;
    private final ClearingMessageLogRepository clearingLogs;
    private final PaymentStateMachine stateMachine;
    private final InboxGuard inbox;
    private final ClearingMessageCodec codec;
    private final GatewayProperties properties;
    private final Ids ids;
    private final Clock clock;

    public ClearingDispatcher(
            PaymentRepository payments,
            ClearingMessageLogRepository clearingLogs,
            PaymentStateMachine stateMachine,
            InboxGuard inbox,
            ClearingMessageCodec codec,
            GatewayProperties properties,
            Ids ids,
            Clock clock) {
        this.payments = payments;
        this.clearingLogs = clearingLogs;
        this.stateMachine = stateMachine;
        this.inbox = inbox;
        this.codec = codec;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 이벤트를 받아 pacs.008 을 준비한다.
     *
     * @param eventId Kafka 헤더의 아웃박스 이벤트 ID — inbox dedup 키
     * @return 송신할 메시지. 이미 처리했거나 보낼 필요가 없으면 비어 있다.
     */
    @Transactional
    public Optional<OutgoingMessage> prepare(String eventId, PaymentValidatedEvent event) {
        if (!inbox.claim(properties.consumerGroup(), eventId)) {
            return Optional.empty();
        }

        Optional<Payment> found = payments.findByIdForUpdate(event.paymentId());
        if (found.isEmpty()) {
            log.error("이벤트가 가리키는 결제가 없다 paymentId={} eventId={}", event.paymentId(), eventId);
            return Optional.empty();
        }

        Payment payment = found.get();
        if (payment.status() != PaymentStatus.VALIDATED) {
            // 재전달이거나 이미 청산에 넘어간 건이다. 두 번 보내지 않는 것이 핵심이다.
            log.debug("송신 대상 아님 — 건너뜀 paymentId={} status={}", payment.paymentId(), payment.status());
            return Optional.empty();
        }

        String msgId = ids.newClearingMsgId();
        Pacs008 message = build(payment, msgId);
        String payload = codec.encode(message);

        stateMachine.transition(payment, PaymentStatus.SENT_TO_CLEARING, msgId, "pacs.008 송신");
        clearingLogs.save(new ClearingMessageLog(
                msgId,
                payment.paymentId(),
                payment.endToEndId(),
                ClearingMsgType.PACS_008,
                Direction.OUT,
                payload,
                clock.instant()));

        return Optional.of(new OutgoingMessage(msgId, ClearingMsgType.PACS_008, payment.endToEndId(), payload));
    }

    private Pacs008 build(Payment payment, String msgId) {
        return new Pacs008(
                new Pacs008.GrpHdr(msgId, clock.instant(), 1, properties.memberId(), payment.creditorBank()),
                new Pacs008.CdtTrfTxInf(
                        // endToEndId 는 재송신해도 바뀌지 않는다 — 청산망의 중복 판정 기준(docs §4.3).
                        new Pacs008.PmtId(payment.endToEndId(), msgId),
                        new Money(payment.currency(), payment.amount()),
                        payment.debtorAccount(),
                        properties.memberId(),
                        payment.creditorAccount(),
                        payment.creditorBank(),
                        payment.remittanceInfo()));
    }
}
