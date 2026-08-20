package kr.paycore.core.intake;

/**
 * 접수 요청 (docs §5.1 축약 pain.001).
 *
 * <p>payment-api 가 입력 검증을 끝낸 뒤 넘기는 값이다. core 는 여기서 다시 포맷 검증을 하지 않는다.
 */
public record IntakeCommand(
        String debtorAccount,
        String creditorAccount,
        String creditorBankCode,
        long amount,
        String currency,
        String remittanceInfo) {

    /**
     * 같은 Idempotency-Key 로 <b>다른 본문</b>이 들어온 경우를 잡아내기 위한 비교.
     *
     * <p>멱등성은 "같은 요청을 여러 번 보내도 결과가 하나"를 보장하는 것이지, "키만 같으면 아무 요청이나
     * 첫 결과를 돌려준다"가 아니다. 후자를 허용하면 클라이언트 버그가 조용히 잘못된 이체로 이어진다.
     */
    public boolean matches(String debtor, String creditor, String bank, long amt, String ccy, String remittance) {
        return java.util.Objects.equals(debtorAccount, debtor)
                && java.util.Objects.equals(creditorAccount, creditor)
                && java.util.Objects.equals(creditorBankCode, bank)
                && amount == amt
                && java.util.Objects.equals(currency, ccy)
                && java.util.Objects.equals(remittanceInfo, remittance);
    }
}
