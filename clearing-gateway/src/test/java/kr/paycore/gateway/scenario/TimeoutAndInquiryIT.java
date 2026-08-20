package kr.paycore.gateway.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.gateway.support.AbstractGatewayIT;
import kr.paycore.gateway.support.SimulatorProcess;
import kr.paycore.simulator.mode.SimulatorMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시나리오 #2 · #3 (docs §8, §7.3) — <b>이 프로젝트의 핵심</b>.
 *
 * <p>둘 다 "응답이 없다"로 시작하지만 결론이 정반대다. 그 차이를 만들어내는 것이 pacs.028 inquiry 이고,
 * 그래서 timeout 을 실패로 단정하면 안 되는 것이다.
 */
class TimeoutAndInquiryIT extends AbstractGatewayIT {

    @Test
    @DisplayName("#2 응답 유실 + 실제로는 처리됨 → UNKNOWN → inquiry → CLEARED. 이체 1건, 재송신 0회")
    void scenario2_processedButNoResponse() {
        SimulatorProcess.mode(SimulatorMode.PROCESS_BUT_NO_RESPONSE);
        Payment payment = givenValidatedPayment(2_000_000L);

        // 먼저 '모른다'로 간다 — 실패로 가지 않는 것이 핵심이다.
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.UNKNOWN);
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_UNKNOWN))
                .isNotEmpty();

        // 상태조회가 사실을 확인하면 그때 CLEARED 로 확정된다.
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);

        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "UNKNOWN", "CLEARED");

        // ── Phase 3 DoD ──────────────────────────────────────────────
        // 1) 이체는 정확히 1건
        assertThat(SimulatorProcess.transfers().size()).isEqualTo(1);
        assertThat(SimulatorProcess.transfers().find(payment.endToEndId())).isPresent();
        // 2) 재송신 0회 — pacs.008 은 처음 한 번뿐이다
        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);
        // 3) 확인 수단은 조회였다
        assertThat(sentPacs028Count(payment.paymentId())).isGreaterThanOrEqualTo(1);
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).contains("\"confirmedByInquiry\":true"));
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();
    }

    @Test
    @DisplayName("#3 이체 지시 유실 + 실제로 미처리 → UNKNOWN → inquiry(NOOR) → FAILED 확정, 재송신 허용")
    void scenario3_neverReceived() {
        SimulatorProcess.mode(SimulatorMode.DROP_REQUEST);
        Payment payment = givenValidatedPayment(3_000_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.UNKNOWN);
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.FAILED);

        assertThat(historyOf(payment.paymentId()))
                .extracting(h -> h.toStatus().name())
                .containsExactly("RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "UNKNOWN", "FAILED");

        // 청산망은 이 이체를 모른다 — 돈이 나가지 않았다.
        assertThat(SimulatorProcess.transfers().find(payment.endToEndId())).isEmpty();
        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);

        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.payload()).contains("\"reasonCode\":\"NOOR\"");
                    assertThat(e.payload()).contains("\"confirmedByInquiry\":true");
                    // '받은 적 없음'을 확인했을 때에만 재송신을 논할 수 있다(docs §7.3).
                    assertThat(e.payload()).contains("\"resendPermitted\":true");
                });
    }

    @Test
    @DisplayName("늦은 응답: DELAY 로 timeout 후 원 응답이 도착해도 상태는 한 번만 확정된다")
    void lateResponseAfterTimeout() {
        SimulatorProcess.mode(SimulatorMode.DELAY, Duration.ofSeconds(4), kr.paycore.common.clearing.StsRsn.AM04);
        Payment payment = givenValidatedPayment(2_500_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.UNKNOWN);
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.CLEARED);
        // 지연됐던 원 pacs.002 가 실제로 도착할 때까지 기다린 뒤에 단정한다 — 안 기다리면 아무것도 증명하지 못한다.
        awaitCondition().until(() -> receivedPacs002Count(payment.paymentId()) == 2);

        // 늦게 도착한 원 pacs.002 도 결국 같은 결론이라 no-op 이다. CLEARED 는 한 번만 기록된다.
        assertThat(historyOf(payment.paymentId()))
                .filteredOn(h -> h.toStatus() == PaymentStatus.CLEARED)
                .hasSize(1);
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .hasSize(1);
        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("조회에도 답이 없으면 추측하지 않고 MANUAL_REVIEW 로 넘긴다")
    void escalatesToManualReviewWhenInquiryNeverAnswered() {
        SimulatorProcess.mode(SimulatorMode.DOWN);
        Payment payment = givenValidatedPayment(4_000_000L);

        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.UNKNOWN);
        awaitCondition().until(() -> statusOf(payment.paymentId()) == PaymentStatus.MANUAL_REVIEW);

        // 정해진 횟수만큼 물어봤다는 사실이 남아 있어야 운영자가 판단할 수 있다.
        assertThat(sentPacs028Count(payment.paymentId())).isEqualTo(3);
        assertThat(sentPacs008Count(payment.paymentId())).isEqualTo(1);
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_MANUAL_REVIEW))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).contains("\"inquiryAttempts\":3"));
        // 자동으로 FAILED 나 CLEARED 로 확정하지 않았다.
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_FAILED))
                .isEmpty();
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED))
                .isEmpty();
    }
}
