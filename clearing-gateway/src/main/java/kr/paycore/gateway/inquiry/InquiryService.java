package kr.paycore.gateway.inquiry;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.common.clearing.Pacs028;
import kr.paycore.common.id.Ids;
import kr.paycore.core.clearing.ClearingMessageLog;
import kr.paycore.core.clearing.ClearingMessageLogRepository;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentManualReviewEvent;
import kr.paycore.core.observability.PaymentMetrics;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.gateway.config.GatewayProperties;
import kr.paycore.gateway.dispatch.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 조회(pacs.028) — "모른다"를 "안다"로 바꾸는 유일한 수단 (docs §5.3, §7.3).
 *
 * <p>재시도 횟수를 별도 컬럼에 두지 않고 {@code CLEARING_MESSAGE_LOG} 의 pacs.028 송신 건수로 센다.
 * 카운터를 따로 두면 "보냈는데 카운터는 안 올랐다" 같은 어긋남이 생기고, 그 어긋남은 조용히
 * 재조회를 무한 반복시키거나 너무 일찍 MANUAL_REVIEW 로 보낸다.
 */
@Service
public class InquiryService {

    private static final String TRIGGERED_BY = "gateway-inquiry";

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private final PaymentRepository payments;
    private final ClearingMessageLogRepository clearingLogs;
    private final PaymentStateMachine stateMachine;
    private final OutboxWriter outbox;
    private final ClearingMessageCodec codec;
    private final GatewayProperties properties;
    private final PaymentMetrics metrics;
    private final Ids ids;
    private final Clock clock;

    public InquiryService(
            PaymentRepository payments,
            ClearingMessageLogRepository clearingLogs,
            PaymentStateMachine stateMachine,
            OutboxWriter outbox,
            ClearingMessageCodec codec,
            GatewayProperties properties,
            PaymentMetrics metrics,
            Ids ids,
            Clock clock) {
        this.payments = payments;
        this.clearingLogs = clearingLogs;
        this.stateMachine = stateMachine;
        this.outbox = outbox;
        this.codec = codec;
        this.properties = properties;
        this.metrics = metrics;
        this.ids = ids;
        this.clock = clock;
    }

    public List<Payment> findUnknown() {
        return payments.findByStatus(PaymentStatus.UNKNOWN, PageRequest.of(0, properties.dispatchBatch()));
    }

    /** 지금 이 건에 무엇을 해야 하는지 판단한다. 판단 근거는 전부 DB 에 남아 있는 사실뿐이다. */
    public InquiryDecision decide(Payment payment) {
        int attempts = attemptsOf(payment.paymentId());
        Instant since = lastInquiryAt(payment.paymentId()).orElse(payment.updatedAt());
        Instant due = since.plus(properties.backoffFor(attempts));

        if (clock.instant().isBefore(due)) {
            return InquiryDecision.WAIT;
        }
        return attempts >= properties.maxInquiryAttempts() ? InquiryDecision.ESCALATE : InquiryDecision.SEND;
    }

    /**
     * pacs.028 을 준비한다 — 기록은 트랜잭션 안에서, 송신은 커밋 이후에.
     *
     * <p>원 이체지시의 {@code msgId} 를 참조하고 {@code endToEndId} 는 그대로 쓴다. 새 이체를 만드는 게
     * 아니라 <b>기존 이체의 결과를 묻는</b> 것이기 때문이다.
     */
    @Transactional
    public Optional<OutgoingMessage> prepareInquiry(String paymentId) {
        Optional<Payment> found = payments.findByIdForUpdate(paymentId);
        if (found.isEmpty() || found.get().status() != PaymentStatus.UNKNOWN) {
            return Optional.empty();
        }
        Payment payment = found.get();

        Optional<ClearingMessageLog> original = clearingLogs.findTopByPaymentIdAndMsgTypeAndDirectionOrderBySentAtDesc(
                paymentId, ClearingMsgType.PACS_008, Direction.OUT);
        if (original.isEmpty()) {
            log.error("원 pacs.008 송신 기록이 없어 조회할 수 없다 paymentId={}", paymentId);
            return Optional.empty();
        }

        String inquiryMsgId = ids.newClearingMsgId();
        Pacs028 inquiry = new Pacs028(
                new Pacs028.GrpHdr(inquiryMsgId, clock.instant()),
                new Pacs028.TxInf(
                        original.get().msgId(),
                        ClearingMsgType.PACS_008,
                        payment.endToEndId(),
                        original.get().msgId()));
        String payload = codec.encode(inquiry);

        clearingLogs.save(new ClearingMessageLog(
                inquiryMsgId,
                paymentId,
                payment.endToEndId(),
                ClearingMsgType.PACS_028,
                Direction.OUT,
                payload,
                clock.instant()));

        metrics.inquirySent(attemptsOf(paymentId));
        log.warn(
                "상태조회 송신 (재송신 아님) paymentId={} endToEndId={} 시도={}회차 원msgId={}",
                paymentId,
                payment.endToEndId(),
                attemptsOf(paymentId),
                original.get().msgId());
        return Optional.of(new OutgoingMessage(inquiryMsgId, ClearingMsgType.PACS_028, payment.endToEndId(), payload));
    }

    /** 조회로도 결론이 나지 않았다. 추측하지 않고 사람에게 넘긴다(docs §7.3 마지막 갈래). */
    @Transactional
    public boolean escalateToManualReview(String paymentId) {
        Optional<Payment> found = payments.findByIdForUpdate(paymentId);
        if (found.isEmpty() || found.get().status() != PaymentStatus.UNKNOWN) {
            return false;
        }
        Payment payment = found.get();
        int attempts = attemptsOf(paymentId);

        stateMachine.transition(payment, PaymentStatus.MANUAL_REVIEW, TRIGGERED_BY, "상태조회 " + attempts + "회 실패");
        outbox.append(
                paymentId,
                PaymentEventType.PAYMENT_MANUAL_REVIEW,
                new PaymentManualReviewEvent(paymentId, payment.endToEndId(), "상태조회 반복 실패", attempts, clock.instant()));
        log.error(
                "상태조회 {}회에도 결론 없음 — MANUAL_REVIEW paymentId={} endToEndId={}",
                attempts,
                paymentId,
                payment.endToEndId());
        return true;
    }

    int attemptsOf(String paymentId) {
        return (int)
                clearingLogs.countByPaymentIdAndMsgTypeAndDirection(paymentId, ClearingMsgType.PACS_028, Direction.OUT);
    }

    private Optional<Instant> lastInquiryAt(String paymentId) {
        return clearingLogs
                .findTopByPaymentIdAndMsgTypeAndDirectionOrderBySentAtDesc(
                        paymentId, ClearingMsgType.PACS_028, Direction.OUT)
                .map(ClearingMessageLog::sentAt);
    }
}
