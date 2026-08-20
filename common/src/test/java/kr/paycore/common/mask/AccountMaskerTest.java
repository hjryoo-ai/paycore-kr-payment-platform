package kr.paycore.common.mask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class AccountMaskerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "110-123-456789, 110-***-***789",
        "352-987-654321, 352-***-***321",
        "110123456789,   110******789",
        "1002-345-678901, 100*-***-***901",
    })
    @DisplayName("앞 3자와 뒤 3자만 남기고 마스킹하며 구분자는 보존한다")
    void masksMiddle(String raw, String expected) {
        assertThat(AccountMasker.mask(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"123456", "12-34", "1"})
    @DisplayName("남는 자릿수가 6자 이하면 전부 마스킹한다 — 부분 노출만으로 식별 가능해지기 때문")
    void masksEverythingWhenTooShort(String raw) {
        String masked = AccountMasker.mask(raw);

        assertThat(masked).doesNotContainPattern("[0-9]");
        assertThat(masked).hasSameSizeAs(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null/빈 값은 그대로 통과시킨다 — 마스킹 호출이 NPE 원인이 되면 안 된다")
    void passesThroughNullAndEmpty(String raw) {
        assertThat(AccountMasker.mask(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("마스킹 결과에 원본 계좌번호가 부분 문자열로 남지 않는다")
    void maskedValueDoesNotContainRaw() {
        String raw = "110-123-456789";

        assertThat(AccountMasker.mask(raw)).doesNotContain("123").doesNotContain("456");
    }
}
