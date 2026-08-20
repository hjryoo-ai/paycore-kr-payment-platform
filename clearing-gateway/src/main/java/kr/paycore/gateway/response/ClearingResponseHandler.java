package kr.paycore.gateway.response;

import java.time.Clock;
import java.util.Optional;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.core.clearing.ClearingMessageLog;
import kr.paycore.core.clearing.ClearingMessageLogRepository;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentFailedEvent;
import kr.paycore.core.event.PaymentUnknownEvent;
import kr.paycore.core.inbox.InboxGuard;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.IllegalStateTransitionException;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pacs.002 수신 처리 (docs §5.3, §7.4).
 *
 * <p>한 트랜잭션에 inbox 선점 · 수신 원문 기록 · 상태 전이 · 아웃박스가 함께 커밋된다.
 *
 * <p>세 가지 방어가 겹쳐 있다.
 *
 * <ol>
 *   <li><b>inbox dedup</b> — 같은 pacs.002 가 두 번 와도 한 번만 처리한다(시나리오 #4).
 *   <li><b>우리가 보낸 메시지에 대한 응답인가</b> — {@code orgnlMsgId} 가 우리 송신 로그에 없으면 무시한다.
 *   <li><b>확정 상태를 덮어쓰지 않는다</b> — 늦게 도착한 모순된 응답은 로그·알림으로만 남긴다(§7.4).
 * </ol>
 */
@Service
public class ClearingResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(ClearingResponseHandler.class);

    private final PaymentRepository payments;
    private final ClearingMessageLogRepository clearingLogs;
    private final PaymentStateMachine stateMachine;
    private final InboxGuard inbox;
    private final OutboxWriter outbox;
    private final GatewayProperties properties;
    private final Clock clock;

    public ClearingResponseHandler(
            PaymentRepository payments,
            ClearingMessageLogRepository clearingLogs,
            PaymentStateMachine stateMachine,
            InboxGuard inbox,
            OutboxWriter outbox,
            GatewayProperties properties,
            Clock clock) {
        this.payments = payments;
        this.clearingLogs = clearingLogs;
        this.stateMachine = stateMachine;
        this.inbox = inbox;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void handle(Pacs002 response, String rawPayload) {
        String consumerGroup = properties.consumerGroup() + "-jms";
        if (!inbox.claim(consumerGroup, response.msgId())) {
            return;
        }

        String endToEndId = response.endToEndId();
        Optional<Payment> found = payments.findByEndToEndIdForUpdate(endToEndId);
        if (found.isEmpty()) {
            log.error("우리 결제가 아닌 endToEndId 에 대한 응답 — 무시한다 endToEndId={}", endToEndId);
            return;
        }
        Payment payment = found.get();

        String orgnlMsgId = response.txInfAndSts().orgnlMsgId();
        if (!clearingLogs.existsById(orgnlMsgId)) {
            // 우리가 보낸 적 없는 메시지에 대한 응답을 받아들이면, 상대가 상태를 마음대로 바꿀 수 있게 된다.
            log.error("송신 이력이 없는 원메시지에 대한 응답 — 무시한다 orgnlMsgId={} endToEndId={}", orgnlMsgId, endToEndId);
            return;
        }

        clearingLogs.save(new ClearingMessageLog(
                response.msgId(),
                payment.paymentId(),
                endToEndId,
                ClearingMsgType.PACS_002,
                Direction.IN,
                rawPayload,
                clock.instant()));

        ClearingOutcome outcome = interpret(response);
        if (!outcome.isDecided()) {
            log.info(
                    "확정되지 않은 응답 — 상태를 바꾸지 않는다 endToEndId={} sts={} 사유={}",
                    endToEndId,
                    response.status(),
                    outcome.reason());
            return;
        }

        boolean changed;
        try {
            changed = stateMachine.transition(payment, outcome.target(), response.msgId(), outcome.reason());
        } catch (IllegalStateTransitionException e) {
            // §7.4 — 확정된 상태를 늦게 온 응답이 덮어쓰지 않는다. 사실만 남기고 사람을 부른다.
            log.error(
                    "청산 응답이 확정 상태와 모순된다 — 자동으로 덮어쓰지 않는다 paymentId={} 현재={} 응답={} msgId={}",
                    payment.paymentId(),
                    payment.status(),
                    outcome.target(),
                    response.msgId());
            return;
        }
        if (!changed) {
            // 같은 결론의 재전달. 이벤트를 또 내면 하류(원장)가 두 번 움직인다.
            return;
        }

        emit(payment, response, outcome);
    }

    /** pacs.002 를 상태 언어로 번역한다. 여기가 "timeout ≠ 실패"의 반대편 — "응답 ≠ 확정" 지점이다. */
    private ClearingOutcome interpret(Pacs002 response) {
        TxSts sts = response.status();
        StsRsn rsn = response.txInfAndSts().stsRsn();

        if (sts == TxSts.ACSC) {
            return new ClearingOutcome(PaymentStatus.CLEARED, "ACSC", "청산 완료", false);
        }
        if (sts != TxSts.RJCT) {
            // ACSP/PDNG 는 '처리 중'이다. 이걸로 상태를 확정하면 그 자체가 오지급 또는 오실패다.
            return ClearingOutcome.hold("미확정 응답 " + sts);
        }
        if (rsn == StsRsn.DUPL) {
            // 우리는 재송신한 적이 없는데 청산망은 중복이라 한다. 원거래 결과를 모르므로 UNKNOWN 으로 두고
            // inquiry 가 사실을 확인하게 한다. 여기서 FAILED 로 단정하면 이미 나간 돈을 실패로 기록하게 된다.
            log.error("청산망이 DUPL 로 거절했다 — 재송신 경로를 점검해야 한다 endToEndId={}", response.endToEndId());
            return new ClearingOutcome(PaymentStatus.UNKNOWN, "DUPL", "청산망 중복 거절 — 원거래 결과 확인 필요", false);
        }
        if (rsn == StsRsn.NOOR) {
            // '받은 적 없음' — 유일하게 재송신이 안전하다고 말할 수 있는 응답이다(docs §7.3).
            return new ClearingOutcome(PaymentStatus.FAILED, "NOOR", "청산망 미수신 확정", true);
        }
        return new ClearingOutcome(PaymentStatus.FAILED, rsn == null ? "RJCT" : rsn.name(), "청산망 거절", false);
    }

    private void emit(Payment payment, Pacs002 response, ClearingOutcome outcome) {
        boolean byInquiry = response.answersInquiry();
        String orgnlTxId = response.txInfAndSts().orgnlTxId();

        switch (outcome.target()) {
            case CLEARED ->
                outbox.append(
                        payment.paymentId(),
                        PaymentEventType.PAYMENT_CLEARED,
                        new PaymentClearedEvent(
                                payment.paymentId(),
                                payment.endToEndId(),
                                orgnlTxId,
                                payment.amount(),
                                payment.currency(),
                                payment.debtorAccount(),
                                payment.creditorAccount(),
                                byInquiry,
                                clock.instant()));
            case FAILED ->
                outbox.append(
                        payment.paymentId(),
                        PaymentEventType.PAYMENT_FAILED,
                        new PaymentFailedEvent(
                                payment.paymentId(),
                                payment.endToEndId(),
                                orgnlTxId,
                                outcome.reasonCode(),
                                outcome.reason(),
                                byInquiry,
                                outcome.resendPermitted(),
                                clock.instant()));
            case UNKNOWN ->
                outbox.append(
                        payment.paymentId(),
                        PaymentEventType.PAYMENT_UNKNOWN,
                        new PaymentUnknownEvent(
                                payment.paymentId(),
                                payment.endToEndId(),
                                orgnlTxId,
                                payment.amount(),
                                payment.updatedAt(),
                                clock.instant()));
            default -> log.warn("이벤트를 정의하지 않은 전이 target={}", outcome.target());
        }
    }
}
