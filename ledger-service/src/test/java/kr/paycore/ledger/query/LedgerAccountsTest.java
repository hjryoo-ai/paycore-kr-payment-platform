package kr.paycore.ledger.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 마스킹 대상은 고객 계좌번호뿐이다 — 내부 계정과목을 가려도 보호되는 것이 없다. */
class LedgerAccountsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CLEARING_SUSPENSE", "FEE_INCOME", "SETTLEMENT"})
    @DisplayName("내부 계정과목은 그대로 보여 준다")
    void keepsInternalAccountsReadable(String accountId) {
        assertThat(LedgerAccounts.display(accountId)).isEqualTo(accountId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"110-123-456789", "1002345678901", "352-987-654321"})
    @DisplayName("고객 계좌번호는 마스킹한다 — 원본이 그대로 남지 않는다")
    void masksCustomerAccounts(String accountId) {
        String masked = LedgerAccounts.display(accountId);

        assertThat(masked).isNotEqualTo(accountId).contains("*");
    }

    @Test
    @DisplayName("빈 값은 마스킹 경로를 타되 예외가 되지 않는다")
    void handlesBlank() {
        assertThat(LedgerAccounts.display("")).isEmpty();
        assertThat(LedgerAccounts.display(null)).isNull();
    }
}
