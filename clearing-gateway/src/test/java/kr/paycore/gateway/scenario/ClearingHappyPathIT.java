package kr.paycore.gateway.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.gateway.support.AbstractGatewayIT;
import kr.paycore.gateway.support.SimulatorProcess;
import kr.paycore.simulator.mode.SimulatorMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정상 흐름과 거절 흐름 (docs §4.1, §5.3). */
class ClearingHappyPathIT extends AbstractGatewayIT {

    @Test
    @DisplayName("VALIDATED → pacs.008 송신 → ACSC 수신 → CLEARED. 송신 기록이 상태보다 먼저 남는다")
    void happyPath() {
        Payment payment = givenValidatedPayment(1_500_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);

        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "CLEARED");

        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);
        assertThat(clearingLogOf(payment.paymentId()))
                .extracting(l -> l.msgType() + "/" + l.direction())
                .containsExactly(
                        ClearingMsgType.PACS_008 + "/" + Direction.OUT, ClearingMsgType.PACS_002 + "/" + Direction.IN);

        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).contains("\"confirmedByInquiry\":false"));

        // 청산망 쪽에서도 이체는 정확히 1건이다.
        assertThat(SimulatorProcess.transfers().find(payment.endToEndId())).isPresent();
        assertThat(SimulatorProcess.transfers().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("RJCT(AM04) 를 받으면 FAILED 로 확정하되 재송신은 허용하지 않는다")
    void rejectedByClearing() {
        SimulatorProcess.mode(SimulatorMode.REJECT, java.time.Duration.ofSeconds(1), StsRsn.AM04);
        Payment payment = givenValidatedPayment(9_000_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.FAILED);

        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.payload()).contains("\"reasonCode\":\"AM04\"");
                    // 청산망이 '받아서 거절'했다. 같은 이체를 다시 보내는 것은 우리 마음대로 할 일이 아니다.
                    assertThat(e.payload()).contains("\"resendPermitted\":false");
                });
        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 여러 건이 섞여도 각자 올바른 상태로 수렴한다")
    void multiplePaymentsConverge() {
        Payment first = givenValidatedPayment(1_000_001L);
        Payment second = givenValidatedPayment(1_000_002L);
        Payment third = givenValidatedPayment(1_000_003L);

        awaitCondition()
                .until(() -> statusOf(first.paymentId()) == PaymentStatus.CLEARED
                        && statusOf(second.paymentId()) == PaymentStatus.CLEARED
                        && statusOf(third.paymentId()) == PaymentStatus.CLEARED);

        assertThat(SimulatorProcess.transfers().size()).isEqualTo(3);
        assertThat(sentPacs008Count(first.paymentId())).isEqualTo(1);
        assertThat(sentPacs008Count(second.paymentId())).isEqualTo(1);
        assertThat(sentPacs008Count(third.paymentId())).isEqualTo(1);
    }
}
