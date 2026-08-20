package kr.paycore.recon.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.recon.BreakStatus;
import kr.paycore.core.recon.BreakType;
import kr.paycore.core.recon.ReconBreak;
import kr.paycore.recon.source.EodFormatException;
import kr.paycore.recon.support.AbstractReconIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 Oracle 위에서 도는 일마감 대사 (docs §5.6, 시나리오 #8). */
class ReconBatchIT extends AbstractReconIT {

    @Autowired
    private ReconService reconService;

    @Test
    @DisplayName("세 출처가 일치하는 날은 불일치가 0건이고 리포트가 그렇게 적힌다")
    void cleanDay() {
        LocalDate date = today();
        Payment settled = givenPayment(PaymentStatus.SETTLED, 1_500_000L);
        givenJournal(settled);
        givenClearingEod(date, List.of(acsc(settled)));

        ReconSummary summary = reconService.run(date);

        assertThat(summary.clean()).isTrue();
        assertThat(summary.ourCount()).isEqualTo(1);
        assertThat(summary.clearingCount()).isEqualTo(1);
        assertThat(summary.ledgerCount()).isEqualTo(1);
        assertThat(breaks.count()).isZero();
        assertThat(readReport(summary.reportFile())).contains("불일치 없음");
    }

    @Test
    @DisplayName("#8 UNKNOWN 을 방치한 채 마감하면 MISSING_AT_US 로 검출된다")
    void scenario8_unattendedUnknownIsDetected() {
        LocalDate date = today();
        Payment unknown = givenPayment(PaymentStatus.UNKNOWN, 2_000_000L);
        // 청산망은 처리했다고 말한다 — 돈은 나갔는데 우리만 모르고 있는 상태다.
        givenClearingEod(date, List.of(acsc(unknown)));

        ReconSummary summary = reconService.run(date);

        assertThat(summary.openBreaks()).isEqualTo(1);
        assertThat(summary.breaksByType()).containsEntry(BreakType.MISSING_AT_US.name(), 1);

        List<ReconBreak> found = breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date);
        assertThat(found).singleElement().satisfies(b -> {
            assertThat(b.breakType()).isEqualTo(BreakType.MISSING_AT_US);
            assertThat(b.paymentId()).isEqualTo(unknown.paymentId());
            assertThat(b.status()).isEqualTo(BreakStatus.OPEN);
            assertThat(b.detail()).contains("ACSC").contains("UNKNOWN");
        });

        String report = readReport(summary.reportFile());
        assertThat(report)
                .contains("MISSING_AT_US")
                .contains(unknown.paymentId())
                .contains("pacs.028 조회 이력을 먼저 확인한다");
    }

    @Test
    @DisplayName("우리는 지급 완료인데 청산망 파일에 없으면 MISSING_AT_CLEARING")
    void missingAtClearing() {
        LocalDate date = today();
        Payment settled = givenPayment(PaymentStatus.SETTLED, 3_000_000L);
        givenJournal(settled);
        givenClearingEod(date, List.of());

        reconService.run(date);

        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .extracting(ReconBreak::breakType)
                .containsExactly(BreakType.MISSING_AT_CLEARING);
    }

    @Test
    @DisplayName("금액이 다르면 AMOUNT_MISMATCH, 분개가 없으면 LEDGER_MISMATCH — 한 건에서 둘 다 잡힌다")
    void amountAndLedgerMismatch() {
        LocalDate date = today();
        Payment settled = givenPayment(PaymentStatus.SETTLED, 1_000_000L);
        givenClearingEod(date, List.of(new EodLine(settled.endToEndId(), 999_999L, "ACSC", null)));

        reconService.run(date);

        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .extracting(ReconBreak::breakType)
                .containsExactlyInAnyOrder(BreakType.AMOUNT_MISMATCH, BreakType.LEDGER_MISMATCH);
    }

    @Test
    @DisplayName("분개 합계가 어긋나면 LEDGER_MISMATCH 로 잡힌다")
    void unbalancedJournalIsDetected() {
        LocalDate date = today();
        Payment settled = givenPayment(PaymentStatus.SETTLED, 1_200_000L);
        givenJournal(settled, 1_200_000L, 1_199_999L);
        givenClearingEod(date, List.of(acsc(settled)));

        reconService.run(date);

        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .extracting(ReconBreak::breakType)
                .contains(BreakType.LEDGER_MISMATCH);
        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .anySatisfy(b -> assertThat(b.detail()).contains("분개 합계 불일치"));
    }

    @Test
    @DisplayName("우리는 FAILED 인데 청산망은 ACSC 면 STATUS_MISMATCH — 돈은 나갔는데 실패로 적혀 있다")
    void statusMismatch() {
        LocalDate date = today();
        Payment failed = givenPayment(PaymentStatus.FAILED, 4_000_000L);
        givenClearingEod(date, List.of(acsc(failed)));

        reconService.run(date);

        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .extracting(ReconBreak::breakType)
                .containsExactly(BreakType.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("재실행해도 OPEN 이 중복되지 않는다 — 그러나 RESOLVED 는 지우지 않는다")
    void rerunReplacesOpenButKeepsResolved() {
        LocalDate date = today();
        Payment unknown = givenPayment(PaymentStatus.UNKNOWN, 2_100_000L);
        givenClearingEod(date, List.of(acsc(unknown)));

        reconService.run(date);
        assertThat(breaks.countByReconDateAndStatus(date, BreakStatus.OPEN)).isEqualTo(1);

        // 운영자가 하나를 처리했다고 표시한다.
        ReconBreak resolved =
                breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date).getFirst();
        tx.executeWithoutResult(s -> {
            ReconBreak managed = breaks.findById(resolved.breakId()).orElseThrow();
            managed.resolve();
            breaks.save(managed);
        });

        reconService.run(date);

        // 같은 불일치가 다시 OPEN 으로 만들어지되, 처리 기록은 남아 있다.
        assertThat(breaks.countByReconDateAndStatus(date, BreakStatus.RESOLVED)).isEqualTo(1);
        assertThat(breaks.countByReconDateAndStatus(date, BreakStatus.OPEN)).isEqualTo(1);

        // 세 번째 실행에서도 OPEN 이 누적되지 않는다.
        reconService.run(date);
        assertThat(breaks.countByReconDateAndStatus(date, BreakStatus.OPEN)).isEqualTo(1);
    }

    @Test
    @DisplayName("EOD 파일이 없으면 마감을 세운다 — 못 받은 것을 0건으로 처리하면 전 건이 불일치가 된다")
    void refusesToReconcileWithoutClearingFile() {
        LocalDate date = today();
        givenPayment(PaymentStatus.SETTLED, 1_000_000L);

        assertThatThrownBy(() -> reconService.run(date)).isInstanceOf(EodFormatException.class);

        assertThat(breaks.count()).as("불일치를 만들지 않는다").isZero();
    }

    @Test
    @DisplayName("청산망 파일에만 있는 건은 paymentId 없이 남는다 — 가장 조용히 지나가기 쉬운 불일치다")
    void orphanAtClearingIsRecorded() {
        LocalDate date = today();
        givenClearingEod(date, List.of(new EodLine("PC-UNKNOWN-TO-US", 5_000_000L, "ACSC", null)));

        reconService.run(date);

        assertThat(breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.breakType()).isEqualTo(BreakType.MISSING_AT_US);
                    assertThat(b.paymentId()).isNull();
                    assertThat(b.detail()).contains("우리 DB 에 해당 endToEndId 없음");
                });
    }
}
