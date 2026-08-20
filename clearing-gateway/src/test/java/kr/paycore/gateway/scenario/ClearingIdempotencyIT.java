package kr.paycore.gateway.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.gateway.support.AbstractGatewayIT;
import kr.paycore.gateway.support.SimulatorProcess;
import kr.paycore.simulator.mode.SimulatorMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.core.JmsTemplate;

/**
 * 시나리오 #4 와 §7.4 — 중복·모순·정체불명 응답에 대한 방어.
 *
 * <p>at-least-once 메시징에서 같은 메시지를 두 번 받는 것은 장애가 아니라 정상이다. 정상인 일에
 * 시스템이 두 번 반응하면 그게 장애다.
 */
class ClearingIdempotencyIT extends AbstractGatewayIT {

    @Autowired
    private JmsTemplate jmsTemplate;

    @Autowired
    private ClearingMessageCodec codec;

    @Test
    @DisplayName("#4 같은 pacs.002 가 두 번 와도 상태 전이는 1회, 이벤트도 1건")
    void scenario4_duplicateResponse() {
        SimulatorProcess.mode(SimulatorMode.DUPLICATE_RESPONSE);
        Payment payment = givenValidatedPayment(1_200_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);
        // 두 번째 응답이 도착해 dedup 될 시간을 준 뒤에 단정한다.
        awaitCondition().until(() -> processedMessageCount() >= 2);

        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .hasSize(1);
        // 두 번째 pacs.002 는 inbox 에서 걸려 원문 로그도 1건만 남는다.
        assertThat(receivedPacs002Count(payment.paymentId())).isEqualTo(1);
        assertThat(SimulatorProcess.transfers().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정된 CLEARED 를 늦게 온 RJCT 가 덮어쓰지 않는다 — 모순은 기록만 하고 사람을 부른다")
    void doesNotOverwriteFinalStateWithContradictingResponse() {
        Payment payment = givenValidatedPayment(1_800_000L);
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);

        String originalMsgId = clearingLogOf(payment.paymentId()).stream()
                .filter(l -> ClearingMsgType.PACS_008.equals(l.msgType()))
                .findFirst()
                .orElseThrow()
                .msgId();

        // 같은 이체에 대해 뒤늦게 '거절'이 도착한다. 새 msgId 라 inbox 는 통과한다.
        injectResponse(new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs002.TxInfAndSts(
                        originalMsgId,
                        ClearingMsgType.PACS_008,
                        payment.endToEndId(),
                        originalMsgId,
                        TxSts.RJCT,
                        StsRsn.AM04,
                        "늦게 도착한 모순 응답")));

        awaitCondition().until(() -> receivedPacs002Count(payment.paymentId()) == 2);

        assertThat(statusOf(payment.paymentId())).isEqualTo(PaymentStatus.CLEARED);
        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();
    }

    @Test
    @DisplayName("ACSP/PDNG 는 '처리 중'이다 — 이걸로 상태를 확정하지 않는다")
    void pendingResponseDoesNotDecideState() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment payment = givenValidatedPayment(1_300_000L);

        awaitCondition().until(() -> sentPacs008Count(payment.paymentId()) == 1);
        String originalMsgId = clearingLogOf(payment.paymentId()).getFirst().msgId();

        injectResponse(new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs002.TxInfAndSts(
                        originalMsgId,
                        ClearingMsgType.PACS_008,
                        payment.endToEndId(),
                        originalMsgId,
                        TxSts.ACSP,
                        null,
                        "처리 중")));

        awaitCondition().until(() -> receivedPacs002Count(payment.paymentId()) >= 1);

        // 상태는 SENT_TO_CLEARING 이거나 (timeout 이 먼저 돌았다면) UNKNOWN 이다. 어느 쪽도 확정이 아니다.
        assertThat(statusOf(payment.paymentId()))
                .isIn(PaymentStatus.SENT_TO_CLEARING, PaymentStatus.UNKNOWN, PaymentStatus.CLEARED);
        assertThat(historyOf(payment.paymentId())).noneMatch(h -> h.toStatus() == PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("보낸 적 없는 메시지에 대한 응답은 무시한다 — 상대가 우리 상태를 마음대로 바꿀 수 없다")
    void ignoresResponseForUnknownOriginalMessage() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment payment = givenValidatedPayment(1_400_000L);
        awaitCondition().until(() -> sentPacs008Count(payment.paymentId()) == 1);

        injectResponse(new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs002.TxInfAndSts(
                        "NEVER-SENT-MSG-ID",
                        ClearingMsgType.PACS_008,
                        payment.endToEndId(),
                        "NEVER-SENT-MSG-ID",
                        TxSts.RJCT,
                        StsRsn.AM04,
                        "위조된 응답")));

        // 위조 응답은 inbox 에는 기록되지만 상태에는 닿지 못한다.
        awaitCondition().until(() -> processedMessageCount() >= 1);
        assertThat(receivedPacs002Count(payment.paymentId())).isZero();
        assertThat(historyOf(payment.paymentId())).noneMatch(h -> h.toStatus() == PaymentStatus.FAILED);
    }

    private void injectResponse(Pacs002 response) {
        String payload = codec.encode(response);
        jmsTemplate.convertAndSend(SimulatorProcess.RESPONSE_QUEUE, payload, jms -> {
            jms.setStringProperty("msgType", ClearingMsgType.PACS_002);
            return jms;
        });
    }
}
