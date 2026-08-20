package kr.paycore.ledger.query;

/** 분개 명세 한 줄. 계좌번호는 응답에서도 마스킹한다(CLAUDE.md). */
public record EntryView(String entryId, String accountId, String drCr, long amount) {

    public EntryView {
        accountId = LedgerAccounts.display(accountId);
    }
}
