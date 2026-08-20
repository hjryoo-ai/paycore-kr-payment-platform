package kr.paycore.gateway.inquiry;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.core.clearing.ClearingMessageLog;
import kr.paycore.core.clearing.ClearingMessageLogRepository;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentUnknownEvent;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응답 timeout 감지 (docs §5.3, §7.3).
 *
 * <p><b>여기서 하지 않는 일이 더 중요하다: 실패 처리하지 않고, 재송신하지 않는다.</b>
 * timeout 은 "실패"가 아니라 "모른다"이고, 모르는 상태에서 다시 보내는 것이 이중 지급의 시작이다.
 * 이 서비스는 상태를 {@code UNKNOWN} 으로 옮기고 {@code PaymentUnknown} 을 남길 뿐이며,
 * 사실 확인은 {@link InquiryService} 의 pacs.028 이 한다.
 */
@Service
public class ClearingTimeoutService {

    private static final String TRIGGERED_BY = "gateway-timeout";

    private static final Logger log = LoggerFactory.getLogger(ClearingTimeoutService.class);

    private final PaymentRepository payments;
    private final ClearingMessageLogRepository clearingLogs;
    private final PaymentStateMachine stateMachine;
    private final OutboxWriter outbox;
    private final GatewayProperties properties;
    private final Clock clock;

    public ClearingTimeoutService(
            PaymentRepository payments,
            ClearingMessageLogRepository clearingLogs,
            PaymentStateMachine stateMachine,
            OutboxWriter outbox,
            GatewayProperties properties,
            Clock clock) {
        this.payments = payments;
        this.clearingLogs = clearingLogs;
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /** 응답을 기다리다 시간이 다한 건들. */
    public List<Payment> findTimedOut() {
        return payments.findByStatusAndUpdatedAtLessThan(
                PaymentStatus.SENT_TO_CLEARING,
                clock.instant().minus(properties.responseTimeout()),
                PageRequest.of(0, properties.dispatchBatch()));
    }

    /**
     * 한 건을 UNKNOWN 으로 옮긴다. 건마다 독립 트랜잭션이라 한 건의 실패가 나머지를 막지 않는다.
     *
     * @return 실제로 전이했으면 true
     */
    @Transactional
    public boolean markUnknown(String paymentId) {
        Optional<Payment> found = payments.findByIdForUpdate(paymentId);
        if (found.isEmpty()) {
            return false;
        }
        Payment payment = found.get();
        if (payment.status() != PaymentStatus.SENT_TO_CLEARING) {
            // 잠금을 기다리는 사이에 응답이 도착했을 수 있다. 그렇다면 건드릴 이유가 없다.
            return false;
        }

        Optional<ClearingMessageLog> sent = clearingLogs.findTopByPaymentIdAndMsgTypeAndDirectionOrderBySentAtDesc(
                paymentId, ClearingMsgType.PACS_008, Direction.OUT);
        String clearingMsgId = sent.map(ClearingMessageLog::msgId).orElse(null);

        stateMachine.transition(
                payment, PaymentStatus.UNKNOWN, TRIGGERED_BY, "응답 timeout " + properties.responseTimeout());
        outbox.append(
                payment.paymentId(),
                PaymentEventType.PAYMENT_UNKNOWN,
                new PaymentUnknownEvent(
                        payment.paymentId(),
                        payment.endToEndId(),
                        clearingMsgId,
                        payment.amount(),
                        sent.map(ClearingMessageLog::sentAt).orElse(payment.updatedAt()),
                        clock.instant()));
        log.warn(
                "청산 응답 timeout — UNKNOWN 으로 둔다(실패 아님, 재송신 없음) paymentId={} endToEndId={} msgId={}",
                payment.paymentId(),
                payment.endToEndId(),
                clearingMsgId);
        return true;
    }
}
