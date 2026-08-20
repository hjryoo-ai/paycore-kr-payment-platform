package kr.paycore.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import kr.paycore.api.validation.BankAccountValidator;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.Money;
import kr.paycore.common.clearing.Pacs008;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 접수가 받아들이는 계좌번호는 <b>전부</b> pacs.008 로 나갈 수 있어야 한다.
 *
 * <p>이 테스트가 없으면 두 규칙이 조용히 어긋난다. 실제로 그런 일이 있었다: 접수 정규식은 "12-3"
 * 같은 4자리를 통과시키는데 pacs.008 스키마는 6자 미만을 거절해서, 그 결제가 VALIDATED 로 접수된 뒤
 * 어디에도 못 가고 영원히 멈췄다. 한쪽만 고치면 다시 어긋나므로 두 값을 여기서 묶어 둔다.
 */
class AccountContractAlignmentTest {

    private final ClearingMessageCodec codec = new ClearingMessageCodec();
    private final BankAccountValidator validator = new BankAccountValidator();

    private static Pacs008 pacs008With(String account) {
        String msgId = "01M0F70306RVHTGSMZSSNHY4BK";
        return new Pacs008(
                new Pacs008.GrpHdr(msgId, Instant.parse("2026-08-20T09:00:00Z"), 1, "020", "088"),
                new Pacs008.CdtTrfTxInf(
                        new Pacs008.PmtId("PC01M0F7030849K68J3CVMXCJCTF", msgId),
                        Money.krw(1_000_000L),
                        account,
                        "020",
                        account,
                        "088",
                        null));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "1234567890", // 최소 자릿수, 구분자 없음
                "110-123-456789",
                "352-987-654321",
                "1002-345-678901",
                "12345678901234567890" // 최대 자릿수
            })
    @DisplayName("접수가 허용하는 계좌번호는 모두 pacs.008 로 인코딩된다")
    void everyAcceptedAccountCanBeSent(String account) {
        assertThat(validator.isValid(account, null)).as("접수 검증을 통과해야 하는 값이다").isTrue();
        assertThatCode(() -> codec.encode(pacs008With(account))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"12-3", "12-34", "123-4", "123456789"})
    @DisplayName("자릿수가 모자란 계좌번호는 접수 단계에서 거절된다 — 청산 단계까지 가서 멈추지 않는다")
    void tooShortAccountsAreRejectedAtIntake(String account) {
        assertThat(validator.isValid(account, null)).isFalse();
    }

    @Test
    @DisplayName("접수 최소 자릿수와 스키마 minLength 가 어긋나면 실패한다")
    void intakeMinimumMatchesSchemaMinimum() {
        String shortest = "0".repeat(BankAccountValidator.MIN_DIGITS);

        assertThat(validator.isValid(shortest, null)).isTrue();
        // 구분자 없는 최소 자릿수 계좌가 스키마의 minLength 경계다. 여기서 깨지면 둘이 어긋난 것이다.
        assertThatCode(() -> codec.encode(pacs008With(shortest))).doesNotThrowAnyException();
    }
}
