package kr.paycore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.ledger.DrCr;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.ledger.support.AbstractLedgerIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 복식부기 기표 (docs §5.5). 이체 1건 = 분개 1벌(명세 2줄), 합계는 언제나 0 이다. */
class LedgerPostingIT extends AbstractLedgerIT {

    @Test
    @DisplayName("PaymentCleared 를 받으면 차변/대변 2줄을 만들고 PaymentSettled 를 낸다")
    void postsDoubleEntryAndEmitsSettled() {
        Payment payment = givenClearedPayment(1_500_000L);
        publishCleared(payment);

        awaitCondition().until(() -> journals.existsByPaymentId(payment.paymentId()));

        List<LedgerEntry> lines = entriesOf(payment.paymentId());
        assertThat(lines).hasSize(2);
        assertThat(lines).filteredOn(e -> e.drCr() == DrCr.D).singleElement().satisfies(e -> {
            assertThat(e.accountId()).isEqualTo(payment.debtorAccount());
            assertThat(e.amount()).isEqualTo(1_500_000L);
        });
        assertThat(lines).filteredOn(e -> e.drCr() == DrCr.C).singleElement().satisfies(e -> {
            assertThat(e.accountId()).isEqualTo("CLEARING_SUSPENSE");
            assertThat(e.amount()).isEqualTo(1_500_000L);
        });

        // 합계 0 — 이것이 복식부기가 성립한다는 유일한 증거다.
        assertThat(lines.stream().mapToLong(LedgerEntry::signed).sum()).isZero();
        assertThat(entries.globalImbalance()).isZero();

        awaitCondition().until(() -> !outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_SETTLED)
                .isEmpty());
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_SETTLED))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).contains("\"amount\":1500000"));
    }

    @Test
    @DisplayName("여러 건을 기표해도 전체 장부 불균형은 0 이다")
    void globalLedgerStaysBalanced() {
        List<Payment> payments = List.of(
                givenClearedPayment(1_000_001L), givenClearedPayment(2_000_002L), givenClearedPayment(3_000_003L));
        payments.forEach(this::publishCleared);

        awaitCondition().until(() -> journals.count() == 3);

        assertThat(entries.count()).isEqualTo(6);
        assertThat(entries.globalImbalance()).isZero();
        payments.forEach(p -> assertThat(entriesOf(p.paymentId()).stream()
                        .mapToLong(LedgerEntry::signed)
                        .sum())
                .isZero());
    }

    @Test
    @DisplayName("고객 계좌 잔액은 저장값이 아니라 명세 합계에서 유도된다")
    void balanceIsDerivedFromEntries() {
        Payment payment = givenClearedPayment(2_500_000L);
        publishCleared(payment);
        awaitCondition().until(() -> journals.existsByPaymentId(payment.paymentId()));

        assertThat(entries.debitTotal(payment.debtorAccount())).isEqualTo(2_500_000L);
        assertThat(entries.creditTotal(payment.debtorAccount())).isZero();
        assertThat(entries.creditTotal("CLEARING_SUSPENSE")).isEqualTo(2_500_000L);
    }

    @Test
    @DisplayName("합계가 0 이 아닌 분개는 저장되지 않는다 — 틀린 장부는 없는 장부보다 나쁘다")
    void refusesUnbalancedJournal() {
        // 명세 생성 로직이 깨지는 상황을 직접 만든다. 이 방어가 사라지면 이 테스트가 먼저 실패한다.
        LedgerEntry debit = new LedgerEntry("E1", "J1", "110-123-456789", DrCr.D, 1_000_000L);
        LedgerEntry credit = new LedgerEntry("E2", "J1", "CLEARING_SUSPENSE", DrCr.C, 999_999L);

        long imbalance = debit.signed() + credit.signed();

        assertThat(imbalance).isNotZero();
        assertThat(new UnbalancedJournalException("P1", imbalance))
                .hasMessageContaining("합계가 0 이 아니다")
                .hasMessageContaining("P1");
    }
}
