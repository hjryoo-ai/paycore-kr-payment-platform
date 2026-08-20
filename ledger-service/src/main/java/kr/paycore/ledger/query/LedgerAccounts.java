package kr.paycore.ledger.query;

import kr.paycore.common.mask.AccountMasker;

/**
 * 원장 응답의 계좌 표기 규칙.
 *
 * <p>마스킹해야 하는 것은 <b>고객의 계좌번호</b>이지 우리 내부 계정과목이 아니다.
 * {@code CLEARING_SUSPENSE} 를 {@code CLE*****_*****NSE} 로 가리면 보호되는 것은 없고
 * 운영자가 분개를 못 읽게 될 뿐이다. 구분 기준은 숫자 포함 여부다 — 고객 계좌번호는
 * 숫자와 구분자로만 이루어지고(접수 검증이 강제한다), 내부 계정과목은 영문 식별자다.
 */
final class LedgerAccounts {

    private LedgerAccounts() {}

    static String display(String accountId) {
        return isInternalAccount(accountId) ? accountId : AccountMasker.mask(accountId);
    }

    private static boolean isInternalAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return false;
        }
        for (int i = 0; i < accountId.length(); i++) {
            if (Character.isDigit(accountId.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
