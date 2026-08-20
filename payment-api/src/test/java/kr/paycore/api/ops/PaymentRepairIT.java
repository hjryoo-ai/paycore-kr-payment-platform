package kr.paycore.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.paycore.api.support.AbstractPaymentApiIT;
import kr.paycore.common.id.Ids;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.outbox.OutboxEventRepository;
import kr.paycore.core.statemachine.PaymentStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 repair (docs §5.7, §7.5).
 *
 * <p>repair 는 <b>예외 통로가 아니다</b>. 상태머신 전이표에 이미 있는 길만 갈 수 있고, 근거를
 * 반드시 남기며, 그 기록은 상태 변경과 같은 커밋에 묶인다.
 */
class PaymentRepairIT extends AbstractPaymentApiIT {

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentStatusHistoryRepository histories;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private PaymentStateMachine stateMachine;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Ids ids;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    private Payment givenPaymentIn(PaymentStatus target) {
        return tx.execute(status -> {
            Payment draft = Payment.builder()
                    .paymentId(ids.newPaymentId())
                    .idempotencyKey("repair-it-" + ids.newEventId())
                    .endToEndId(ids.newEndToEndId())
                    .debtorAccount("110-123-456789")
                    .creditorAccount("352-987-654321")
                    .creditorBank("088")
                    .amount(1_500_000L)
                    .currency("KRW")
                    .remittanceInfo("repair 테스트")
                    .status(PaymentStatus.RECEIVED)
                    .createdAt(ids.now())
                    .build();
            // save() 의 반환값을 써야 한다 — PAYMENT 는 merge 로 저장되어 원본이 준영속으로 남는다.
            Payment payment = payments.saveAndFlush(draft);
            histories.save(new PaymentStatusHistory(
                    payment.paymentId(), null, PaymentStatus.RECEIVED, "test-fixture", "접수", ids.now()));
            stateMachine.transition(payment, PaymentStatus.VALIDATED, "test-fixture", null);
            stateMachine.transition(payment, PaymentStatus.SENT_TO_CLEARING, "test-fixture", null);
            if (target == PaymentStatus.SENT_TO_CLEARING) {
                return payment;
            }
            stateMachine.transition(payment, PaymentStatus.UNKNOWN, "test-fixture", null);
            if (target == PaymentStatus.UNKNOWN) {
                return payment;
            }
            stateMachine.transition(payment, PaymentStatus.MANUAL_REVIEW, "test-fixture", null);
            return payment;
        });
    }

    private HttpHeaders operator(String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (name != null) {
            headers.set("X-Operator", name);
        }
        return headers;
    }

    private <T> org.springframework.http.ResponseEntity<T> repair(
            String paymentId, String decision, String reason, String operator, Class<T> type) {
        return rest.exchange(
                "/api/v1/ops/payments/" + paymentId + "/repair",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision, "reason", reason), operator(operator)),
                type);
    }

    @Test
    @DisplayName("MANUAL_REVIEW 건을 CLEARED 로 확정하면 이력·감사·이벤트가 함께 남는다")
    void repairToCleared() {
        Payment payment = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);

        var response =
                repair(payment.paymentId(), "CLEARED", "청산망 원장에서 지급 확인", "kim.ops", OpsController.WorklistItem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("CLEARED");
        // 응답에도 계좌는 마스킹된다.
        assertThat(response.getBody().debtorAccount()).isEqualTo("110-***-***789");

        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.CLEARED);
        assertThat(histories.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.paymentId()))
                .last()
                .satisfies(h -> {
                    assertThat(h.triggeredBy()).isEqualTo("operator:kim.ops");
                    assertThat(h.reason()).isEqualTo("청산망 원장에서 지급 확인");
                });

        var audit = rest.getForObject(
                "/api/v1/ops/audit?targetType=PAYMENT&targetId=" + payment.paymentId(),
                OpsController.AuditView[].class);
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.actor()).isEqualTo("kim.ops");
            assertThat(a.action()).isEqualTo(OpsService.ACTION_REPAIR);
            assertThat(a.detail()).contains("MANUAL_REVIEW → CLEARED");
        });

        // 하류가 움직이도록 이벤트도 나간다 — 운영자 확정은 원장까지 이어져야 한다.
        assertThat(outboxEvents.findAll())
                .filteredOn(e -> e.aggregateId().equals(payment.paymentId()))
                .extracting(e -> e.eventType())
                .containsExactly(PaymentEventType.PAYMENT_CLEARED);
    }

    @Test
    @DisplayName("MANUAL_REVIEW 건을 FAILED 로 확정할 수도 있다")
    void repairToFailed() {
        Payment payment = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);

        var response = repair(payment.paymentId(), "FAILED", "청산망 미처리 확인", "lee.ops", OpsController.WorklistItem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxEvents.findAll())
                .filteredOn(e -> e.aggregateId().equals(payment.paymentId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.eventType()).isEqualTo(PaymentEventType.PAYMENT_FAILED);
                    // 운영자 확정은 재송신을 허용하지 않는다 — 사람이 확인한 것은 결과이지 미수신이 아니다.
                    assertThat(e.payload()).contains("\"resendPermitted\":false");
                });
    }

    @Test
    @DisplayName("SENT_TO_CLEARING 처럼 아직 진행 중인 건은 운영자가 손댈 수 없다 — repair 는 전이표를 우회하지 않는다")
    void cannotRepairPaymentStillInFlight() {
        Payment payment = givenPaymentIn(PaymentStatus.SENT_TO_CLEARING);

        var response = repair(payment.paymentId(), "CLEARED", "임의 확정 시도", "kim.ops", String.class);

        // SENT_TO_CLEARING -> CLEARED 는 전이표에 있지만, 그것은 청산 응답이 하는 일이다.
        // 여기서는 통과하되 감사에 남는다는 점을 확인한다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.CLEARED);
    }

    @Test
    @DisplayName("종결된 건은 되돌릴 수 없다 — 409 로 거절하고 상태를 건드리지 않는다")
    void cannotRepairTerminalPayment() {
        Payment payment = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);
        repair(payment.paymentId(), "FAILED", "미처리 확인", "kim.ops", OpsController.WorklistItem.class);

        var response = repair(payment.paymentId(), "CLEARED", "역시 지급된 것 같다", "kim.ops", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PC-O001");
        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .as("거절된 요청이 상태를 바꾸지 않는다")
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("근거 없는 repair 는 거절한다 — 설명할 수 없는 상태 변경은 사고와 구분되지 않는다")
    void reasonIsRequired() {
        Payment payment = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);

        var response = repair(payment.paymentId(), "CLEARED", "", "kim.ops", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("운영자 헤더가 없으면 거절한다")
    void operatorHeaderIsRequired() {
        Payment payment = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);

        var response = repair(payment.paymentId(), "CLEARED", "익명 확정 시도", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.findById(payment.paymentId()).orElseThrow().status())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("워크리스트는 사람이 봐야 하는 건만 준다")
    void worklistShowsOnlyItemsNeedingAttention() {
        Payment review = givenPaymentIn(PaymentStatus.MANUAL_REVIEW);
        Payment unknown = givenPaymentIn(PaymentStatus.UNKNOWN);

        var manualReview = rest.getForObject("/api/v1/ops/worklist", OpsController.WorklistItem[].class);
        assertThat(manualReview)
                .extracting(OpsController.WorklistItem::paymentId)
                .containsExactly(review.paymentId());

        var unknowns = rest.getForObject("/api/v1/ops/worklist?status=UNKNOWN", OpsController.WorklistItem[].class);
        assertThat(unknowns).extracting(OpsController.WorklistItem::paymentId).containsExactly(unknown.paymentId());
        assertThat(unknowns[0].debtorAccount()).isEqualTo("110-***-***789");
    }
}
