package kr.paycore.ledger.query;

import kr.paycore.common.mask.AccountMasker;

/**
 * 계좌 잔액. 저장된 값이 아니라 {@code LEDGER_ENTRY} 합계에서 유도한 값이다 (docs §5.5).
 *
 * @param net 대변 합 − 차변 합. 고객 예금계좌(부채)는 대변이 늘면 잔액이 는다
 */
public record AccountBalanceView(String accountId, long debitTotal, long creditTotal, long net) {

    public AccountBalanceView {
        accountId = AccountMasker.mask(accountId);
    }
}
