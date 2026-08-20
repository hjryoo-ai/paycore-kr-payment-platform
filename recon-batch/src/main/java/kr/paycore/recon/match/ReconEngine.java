package kr.paycore.recon.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.paycore.core.recon.BreakType;
import kr.paycore.recon.source.ClearingEodRecord;

/**
 * 3-way 대사 (docs §5.6). <b>순수 함수다</b> — DB 도 시계도 만지지 않는다.
 *
 * <p>그렇게 만든 이유는 이 로직이 대사의 전부이기 때문이다. 컨테이너를 띄워야만 검증할 수 있는
 * 규칙은 조합을 전수로 확인할 수 없고, 전수로 확인하지 못한 대사 규칙은 그 자체가 불일치의 원인이 된다.
 *
 * <p>세 주장을 맞춰 본다.
 *
 * <ul>
 *   <li><b>우리</b>: {@code PAYMENT.STATUS}
 *   <li><b>청산망</b>: EOD 파일의 {@code status}
 *   <li><b>회계</b>: {@code JOURNAL} + {@code LEDGER_ENTRY}
 * </ul>
 */
public final class ReconEngine {

    private final String suspenseAccount;

    public ReconEngine(String suspenseAccount) {
        this.suspenseAccount = suspenseAccount;
    }

    public List<ReconFinding> reconcile(
            List<PaymentSnapshot> payments, List<ClearingEodRecord> clearingRecords, List<LedgerSnapshot> ledgers) {

        Map<String, ClearingEodRecord> byEndToEndId = clearingRecords.stream()
                .collect(java.util.stream.Collectors.toMap(ClearingEodRecord::endToEndId, r -> r, (a, b) -> a));
        Map<String, LedgerSnapshot> ledgerByPayment = ledgers.stream()
                .collect(
                        java.util.stream.Collectors.toMap(LedgerSnapshot::paymentId, Function.identity(), (a, b) -> a));

        List<ReconFinding> findings = new ArrayList<>();
        for (PaymentSnapshot payment : payments) {
            ClearingEodRecord clearing = byEndToEndId.get(payment.endToEndId());
            matchAgainstClearing(payment, clearing, findings);
            matchAgainstLedger(payment, ledgerByPayment.get(payment.paymentId()), findings);
        }
        findOrphansAtClearing(payments, clearingRecords, findings);

        findings.sort(Comparator.comparing((ReconFinding f) -> f.type().name()).thenComparing(ReconFinding::key));
        return List.copyOf(findings);
    }

    private void matchAgainstClearing(
            PaymentSnapshot payment, ClearingEodRecord clearing, List<ReconFinding> findings) {

        if (clearing == null) {
            if (payment.believesPaid()) {
                // 우리는 돈이 나갔다고 아는데 상대는 그 이체를 모른다. 유령 지급일 수 있다.
                findings.add(finding(
                        BreakType.MISSING_AT_CLEARING,
                        payment,
                        "우리=" + payment.status() + " 금액=" + payment.amount() + " / 청산망 기록 없음"));
            }
            return;
        }

        if (clearing.amount() != payment.amount()) {
            findings.add(finding(
                    BreakType.AMOUNT_MISMATCH, payment, "우리=" + payment.amount() + " / 청산망=" + clearing.amount()));
        }

        if (clearing.settled()) {
            if (payment.undecided()) {
                // 시나리오 #8 — 방치된 UNKNOWN 이 여기로 잡힌다.
                findings.add(
                        finding(BreakType.MISSING_AT_US, payment, "청산망=ACSC 인데 우리=" + payment.status() + " (결론 미확정)"));
            } else if (payment.believesNotPaid()) {
                findings.add(finding(
                        BreakType.STATUS_MISMATCH,
                        payment,
                        "청산망=ACSC 인데 우리=" + payment.status() + " — 돈은 나갔는데 실패로 기록돼 있다"));
            }
            return;
        }

        if (clearing.rejected()) {
            if (payment.believesPaid()) {
                findings.add(finding(
                        BreakType.STATUS_MISMATCH,
                        payment,
                        "청산망=RJCT(" + clearing.reason() + ") 인데 우리=" + payment.status()));
            } else if (payment.undecided()) {
                findings.add(finding(
                        BreakType.MISSING_AT_US,
                        payment,
                        "청산망=RJCT(" + clearing.reason() + ") 로 결론이 났는데 우리=" + payment.status()));
            }
        }
    }

    private void matchAgainstLedger(PaymentSnapshot payment, LedgerSnapshot ledger, List<ReconFinding> findings) {
        if (ledger == null) {
            if (payment.believesPaid()) {
                findings.add(finding(BreakType.LEDGER_MISMATCH, payment, "우리=" + payment.status() + " 인데 분개가 없다"));
            }
            return;
        }

        if (!payment.believesPaid()) {
            // 돈이 나가지 않았는데 장부에는 나간 것으로 적혀 있다.
            findings.add(finding(
                    BreakType.LEDGER_MISMATCH,
                    payment,
                    "우리=" + payment.status() + " 인데 분개가 있다 journalId=" + ledger.journalId()));
            return;
        }
        if (!ledger.balanced()) {
            findings.add(finding(
                    BreakType.LEDGER_MISMATCH,
                    payment,
                    "분개 합계 불일치 차변=" + ledger.debitTotal() + " 대변=" + ledger.creditTotal()));
        }
        if (ledger.entryCount() != 2) {
            findings.add(finding(BreakType.LEDGER_MISMATCH, payment, "분개 명세가 2줄이 아니다: " + ledger.entryCount() + "줄"));
        }
        if (ledger.debitTotal() != payment.amount()) {
            findings.add(finding(
                    BreakType.LEDGER_MISMATCH,
                    payment,
                    "원장 금액 불일치 결제=" + payment.amount() + " 차변=" + ledger.debitTotal()));
        }
    }

    /** 청산망 파일에는 있는데 우리 DB 에 아예 없는 건. 가장 조용히 지나가기 쉬운 불일치다. */
    private void findOrphansAtClearing(
            List<PaymentSnapshot> payments, List<ClearingEodRecord> clearingRecords, List<ReconFinding> findings) {
        java.util.Set<String> ourEndToEndIds =
                payments.stream().map(PaymentSnapshot::endToEndId).collect(java.util.stream.Collectors.toSet());

        for (ClearingEodRecord clearing : clearingRecords) {
            if (!ourEndToEndIds.contains(clearing.endToEndId())) {
                findings.add(new ReconFinding(
                        BreakType.MISSING_AT_US,
                        null,
                        clearing.endToEndId(),
                        "청산망=" + clearing.status() + " 금액=" + clearing.amount() + " / 우리 DB 에 해당 endToEndId 없음"));
            }
        }
    }

    private static ReconFinding finding(BreakType type, PaymentSnapshot payment, String detail) {
        return new ReconFinding(type, payment.paymentId(), payment.endToEndId(), detail);
    }

    /** 대변에 기대하는 계정. 리포트에 남겨 대사 기준을 드러낸다. */
    public String suspenseAccount() {
        return suspenseAccount;
    }
}
