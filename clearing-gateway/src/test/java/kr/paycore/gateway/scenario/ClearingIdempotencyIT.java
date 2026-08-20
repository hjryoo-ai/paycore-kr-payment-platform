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
import org.springframework.test.context.TestPropertySource;

/**
 * 시나리오 #4 와 §7.4 — 중복 · 모순 · 정체불명 응답에 대한 방어.
 *
 * <p>at-least-once 메시징에서 같은 메시지를 두 번 받는 것은 장애가 아니라 정상이다. 정상인 일에
 * 시스템이 두 번 반응하면 그게 장애다.
 *
 * <p>watchdog 을 꺼 두는 이유: 이 클래스가 보려는 것은 "응답을 어떻게 해석하는가"이지 timeout 처리가
 * 아니다. watchdog 이 돌면 응답을 판정하기도 전에 상태가 UNKNOWN → CLEARED 로 흘러가 버려서,
 * 단정이 느슨해지거나(그래서 버그를 놓치거나) 시간 경합으로 깨진다.
 */
@TestPropertySource(properties = "paycore.gateway.watchdog-enabled=false")
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

        // 중복이 '실제로 도착했음'을 증명해야 한다. 시뮬레이터는 두 번째 사본을 첫 번째 직후에
        // 같은 큐로 보내므로, 그 뒤에 우리가 넣은 표식 메시지가 처리됐다면 중복은 이미 지나간 뒤다
        // (응답 큐는 FIFO, 리스너 동시성 1). 표식 없이 단정하면 중복이 오기도 전에 통과해 버린다.
        String originalMsgId = sentPacs008MsgId(payment.paymentId());
        injectResponse(sentinelAck(payment.endToEndId(), originalMsgId));
        awaitCondition().until(() -> receivedPacs002Count(payment.paymentId()) == 2);

        // 중복 사본은 msgId 가 같아 inbox 에서 걸린다 — 원문 로그도, inbox 행도 늘지 않는다.
        assertThat(processedMessageCount())
                .as("Kafka 이벤트 1 + pacs.002 원본 1 + 표식 1. 중복이 처리됐다면 4가 된다")
                .isEqualTo(3);
        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .hasSize(1);
        assertThat(SimulatorProcess.transfers().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정된 CLEARED 를 늦게 온 RJCT 가 덮어쓰지 않는다 — MANUAL_REVIEW 로 올리고 알린다")
    void escalatesContradictingResponseInsteadOfOverwriting() {
        Payment payment = givenValidatedPayment(1_800_000L);
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);

        String originalMsgId = sentPacs008MsgId(payment.paymentId());

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

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.MANUAL_REVIEW);

        // 상태를 FAILED 로 뒤집지 않았다 — 이미 나간 돈을 실패로 적는 일은 없다.
        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED", "MANUAL_REVIEW");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();

        // 그리고 조용히 넘어가지 않는다 — 운영자가 볼 사실이 이벤트로 남는다.
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.CLEARING_CONTRADICTION))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.payload()).contains("\"currentStatus\":\"CLEARED\"");
                    assertThat(e.payload()).contains("\"respondedStatus\":\"FAILED\"");
                    assertThat(e.payload()).contains("\"escalated\":true");
                });
    }

    @Test
    @DisplayName("ACSP/PDNG 는 '처리 중'이다 — 이걸로 상태를 확정하지 않는다")
    void pendingResponseDoesNotDecideState() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment payment = givenValidatedPayment(1_300_000L);

        awaitCondition().until(() -> sentPacs008Count(payment.paymentId()) == 1);
        String originalMsgId = sentPacs008MsgId(payment.paymentId());

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

        awaitCondition().until(() -> receivedPacs002Count(payment.paymentId()) == 1);

        // watchdog 이 꺼져 있으므로 상태를 움직일 수 있는 것은 이 응답뿐이다. 움직였다면 버그다.
        assertThat(statusOf(payment.paymentId())).isEqualTo(PaymentStatus.SENT_TO_CLEARING);
        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .isEmpty();
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();
    }

    @Test
    @DisplayName("보낸 적 없는 메시지에 대한 응답은 무시한다 — 상대가 우리 상태를 마음대로 바꿀 수 없다")
    void ignoresResponseForUnknownOriginalMessage() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment payment = givenValidatedPayment(1_400_000L);
        awaitCondition().until(() -> sentPacs008Count(payment.paymentId()) == 1);
        String originalMsgId = sentPacs008MsgId(payment.paymentId());

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
        // 위조 응답 뒤에 정상 응답을 넣는다. 뒤엣것이 처리됐다면 앞엣것도 이미 지나간 뒤다.
        injectResponse(sentinelAck(payment.endToEndId(), originalMsgId));

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);

        // 위조 응답은 inbox 에는 남지만 상태에도, 원문 로그에도 닿지 못했다.
        assertThat(receivedPacs002Count(payment.paymentId()))
                .as("정상 응답 1건만 기록된다")
                .isEqualTo(1);
        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED");
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 결제의 msgId 를 실은 응답도 무시한다 — endToEndId 만 맞으면 되는 게 아니다")
    void ignoresResponseCorrelatedToAnotherPayment() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment victim = givenValidatedPayment(1_500_000L);
        Payment other = givenValidatedPayment(1_600_000L);
        awaitCondition()
                .until(() -> sentPacs008Count(victim.paymentId()) == 1 && sentPacs008Count(other.paymentId()) == 1);

        String othersMsgId = sentPacs008MsgId(other.paymentId());

        // 피해자의 endToEndId + 다른 결제의 msgId 조합. 존재 확인만 하면 통과해 버린다.
        injectResponse(new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs002.TxInfAndSts(
                        othersMsgId,
                        ClearingMsgType.PACS_008,
                        victim.endToEndId(),
                        othersMsgId,
                        TxSts.ACSC,
                        null,
                        "짝이 맞지 않는 응답")));
        injectResponse(sentinelAck(other.endToEndId(), othersMsgId));

        awaitCondition().until(() -> statusOf(other.paymentId()) == PaymentStatus.CLEARED);

        assertThat(statusOf(victim.paymentId())).as("피해자는 응답을 받은 적이 없다").isEqualTo(PaymentStatus.SENT_TO_CLEARING);
        assertThat(receivedPacs002Count(victim.paymentId())).isZero();
    }

    /** 정상적인 ACSC 응답. 큐가 어디까지 처리됐는지 재는 표식으로 쓴다. */
    private Pacs002 sentinelAck(String endToEndId, String orgnlMsgId) {
        return new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs002.TxInfAndSts(
                        orgnlMsgId, ClearingMsgType.PACS_008, endToEndId, orgnlMsgId, TxSts.ACSC, null, "표식"));
    }

    private void injectResponse(Pacs002 response) {
        String payload = codec.encode(response);
        jmsTemplate.convertAndSend(SimulatorProcess.RESPONSE_QUEUE, payload, jms -> {
            jms.setStringProperty("msgType", ClearingMsgType.PACS_002);
            return jms;
        });
    }
}
