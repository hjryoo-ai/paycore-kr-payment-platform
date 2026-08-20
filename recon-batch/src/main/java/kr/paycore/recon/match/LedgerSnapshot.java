package kr.paycore.recon.match;

/** 대사 시점의 <b>"회계가 아는 것"</b> (docs §5.6). */
public record LedgerSnapshot(String paymentId, String journalId, long debitTotal, long creditTotal, int entryCount) {

    public boolean balanced() {
        return debitTotal == creditTotal;
    }
}
