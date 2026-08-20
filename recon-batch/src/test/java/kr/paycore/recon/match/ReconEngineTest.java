package kr.paycore.recon.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.recon.BreakType;
import kr.paycore.recon.source.ClearingEodRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 대사 판정 전수 테스트.
 *
 * <p>대사는 "돈이 어디 있는지"를 최종적으로 판정하는 자리다. 여기서 규칙 하나가 잘못되면
 * 진짜 사고가 조용히 닫히거나, 멀쩡한 건으로 사람이 몇 시간을 쓴다. 그래서 컨테이너 없이
 * 조합을 전부 돌린다.
 */
class ReconEngineTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:00:00Z");
    private static final long AMOUNT = 1_500_000L;

    private final ReconEngine engine = new ReconEngine("CLEARING_SUSPENSE");

    private static PaymentSnapshot payment(PaymentStatus status) {
        return new PaymentSnapshot("PID1", "PC-E2E-1", status, AMOUNT, "110-123-456789");
    }

    private static ClearingEodRecord clearing(String status, long amount) {
        return new ClearingEodRecord(
                "PC-E2E-1", "MSG1", "MSG1", "110-123-456789", "352-987-654321", "088", amount, "KRW", status, null, AT);
    }

    private static LedgerSnapshot ledger(long debit, long credit, int entryCount) {
        return new LedgerSnapshot("PID1", "J1", debit, credit, entryCount);
    }

    private List<BreakType> typesOf(
            List<PaymentSnapshot> payments, List<ClearingEodRecord> clearingRecords, List<LedgerSnapshot> ledgers) {
        return engine.reconcile(payments, clearingRecords, ledgers).stream()
                .map(ReconFinding::type)
                .toList();
    }

    @Test
    @DisplayName("세 출처가 모두 일치하면 불일치가 없다")
    void allThreeAgree() {
        assertThat(typesOf(
                        List.of(payment(PaymentStatus.SETTLED)),
                        List.of(clearing("ACSC", AMOUNT)),
                        List.of(ledger(AMOUNT, AMOUNT, 2))))
                .isEmpty();
    }

    @Test
    @DisplayName("우리는 지급 완료로 아는데 청산망 파일에 없으면 MISSING_AT_CLEARING")
    void missingAtClearing() {
        assertThat(typesOf(List.of(payment(PaymentStatus.SETTLED)), List.of(), List.of(ledger(AMOUNT, AMOUNT, 2))))
                .containsExactly(BreakType.MISSING_AT_CLEARING);
    }

    @ParameterizedTest(name = "우리={0}")
    @EnumSource(
            value = PaymentStatus.class,
            names = {"RECEIVED", "VALIDATED", "SENT_TO_CLEARING", "UNKNOWN", "MANUAL_REVIEW"})
    @DisplayName("#8 청산망은 ACSC 인데 우리가 결론을 못 냈으면 MISSING_AT_US")
    void missingAtUsWhenUndecided(PaymentStatus status) {
        assertThat(typesOf(List.of(payment(status)), List.of(clearing("ACSC", AMOUNT)), List.of()))
                .containsExactly(BreakType.MISSING_AT_US);
    }

    @Test
    @DisplayName("청산망 파일에는 있는데 우리 DB 에 아예 없으면 MISSING_AT_US (paymentId 없이)")
    void missingAtUsWhenPaymentUnknownToUs() {
        List<ReconFinding> findings = engine.reconcile(List.of(), List.of(clearing("ACSC", AMOUNT)), List.of());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(BreakType.MISSING_AT_US);
            assertThat(f.paymentId()).isNull();
            assertThat(f.key()).isEqualTo("PC-E2E-1");
        });
    }

    @ParameterizedTest(name = "우리={0}")
    @EnumSource(
            value = PaymentStatus.class,
            names = {"REJECTED", "FAILED"})
    @DisplayName("우리는 실패라 하는데 청산망은 ACSC 면 STATUS_MISMATCH — 가장 위험한 불일치다")
    void statusMismatchWhenWeSayFailedButNetworkPaid(PaymentStatus status) {
        assertThat(typesOf(List.of(payment(status)), List.of(clearing("ACSC", AMOUNT)), List.of()))
                .containsExactly(BreakType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("우리는 지급 완료인데 청산망은 RJCT 면 STATUS_MISMATCH")
    void statusMismatchWhenWePaidButNetworkRejected() {
        assertThat(typesOf(
                        List.of(payment(PaymentStatus.SETTLED)),
                        List.of(clearing("RJCT", AMOUNT)),
                        List.of(ledger(AMOUNT, AMOUNT, 2))))
                .containsExactly(BreakType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("양쪽 다 실패로 알면 불일치가 아니다")
    void bothSidesAgreeOnFailure() {
        assertThat(typesOf(List.of(payment(PaymentStatus.FAILED)), List.of(clearing("RJCT", AMOUNT)), List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("금액이 다르면 AMOUNT_MISMATCH — 상태가 일치해도 잡는다")
    void amountMismatch() {
        assertThat(typesOf(
                        List.of(payment(PaymentStatus.SETTLED)),
                        List.of(clearing("ACSC", AMOUNT + 1)),
                        List.of(ledger(AMOUNT, AMOUNT, 2))))
                .containsExactly(BreakType.AMOUNT_MISMATCH);
    }

    static List<Arguments> ledgerDefects() {
        return List.of(
                Arguments.of("분개 없음", (Object) List.<LedgerSnapshot>of()),
                Arguments.of("합계 불일치", (Object) List.of(ledger(AMOUNT, AMOUNT - 1, 2))),
                Arguments.of("명세가 2줄이 아님", (Object) List.of(ledger(AMOUNT, AMOUNT, 3))),
                Arguments.of("원장 금액 불일치", (Object) List.of(ledger(AMOUNT - 100, AMOUNT - 100, 2))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ledgerDefects")
    @DisplayName("결제는 지급 완료인데 원장이 어긋나면 LEDGER_MISMATCH")
    void ledgerMismatch(String description, List<LedgerSnapshot> ledgers) {
        assertThat(typesOf(List.of(payment(PaymentStatus.SETTLED)), List.of(clearing("ACSC", AMOUNT)), ledgers))
                .contains(BreakType.LEDGER_MISMATCH);
    }

    @Test
    @DisplayName("돈이 나가지 않았는데 분개가 있으면 LEDGER_MISMATCH")
    void ledgerExistsWithoutPayment() {
        assertThat(typesOf(
                        List.of(payment(PaymentStatus.FAILED)),
                        List.of(clearing("RJCT", AMOUNT)),
                        List.of(ledger(AMOUNT, AMOUNT, 2))))
                .containsExactly(BreakType.LEDGER_MISMATCH);
    }

    @Test
    @DisplayName("아직 결론이 안 난 건은 분개가 없어도 원장 불일치가 아니다")
    void undecidedPaymentWithoutLedgerIsNotALedgerBreak() {
        assertThat(typesOf(List.of(payment(PaymentStatus.UNKNOWN)), List.of(), List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("결과는 유형·키 순으로 안정 정렬된다 — 리포트가 실행마다 뒤바뀌면 비교할 수 없다")
    void findingsAreStablySorted() {
        List<PaymentSnapshot> payments = List.of(
                new PaymentSnapshot("P2", "E2", PaymentStatus.SETTLED, AMOUNT, "110-123-456789"),
                new PaymentSnapshot("P1", "E1", PaymentStatus.UNKNOWN, AMOUNT, "110-123-456789"));
        List<ClearingEodRecord> clearingRecords = List.of(new ClearingEodRecord(
                "E1", "M1", "M1", "110-123-456789", "352-987-654321", "088", AMOUNT, "KRW", "ACSC", null, AT));

        List<ReconFinding> first = engine.reconcile(payments, clearingRecords, List.of());
        List<ReconFinding> second = engine.reconcile(payments, clearingRecords, List.of());

        assertThat(first).isEqualTo(second);
        assertThat(first)
                .extracting(ReconFinding::type)
                .containsExactly(BreakType.LEDGER_MISMATCH, BreakType.MISSING_AT_CLEARING, BreakType.MISSING_AT_US);
    }
}
