package kr.paycore.api.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.paycore.api.validation.BankCode;
import kr.paycore.api.validation.MaxTransferAmount;
import kr.paycore.api.validation.SupportedCurrency;

/**
 * 이체 접수 요청 (docs §5.1, 축약 pain.001).
 *
 * <p>계좌번호는 자유 문자열이 아니라 <b>화이트리스트 패턴</b>으로 받는다. 하이픈 포함/미포함 두 형태만
 * 허용하며, 그 밖의 문자는 거부한다 — 로그 인젝션과 downstream 전문 오염을 입구에서 막는다.
 */
public record PaymentIntakeRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = ACCOUNT_PATTERN, message = "계좌번호 형식이 올바르지 않습니다.")
        String debtorAccount,

        @NotBlank @Size(max = 32) @Pattern(regexp = ACCOUNT_PATTERN, message = "계좌번호 형식이 올바르지 않습니다.")
        String creditorAccount,

        @NotBlank @Pattern(regexp = "^[0-9]{3}$", message = "은행코드는 숫자 3자리입니다.") @BankCode
        String creditorBankCode,

        @NotNull @Positive @MaxTransferAmount Long amount,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "통화코드 형식이 올바르지 않습니다.") @SupportedCurrency
        String currency,

        @Size(max = 140) @Pattern(regexp = REMITTANCE_PATTERN, message = "적요에 허용되지 않은 문자가 있습니다.")
        String remittanceInfo) {

    /** 숫자만, 또는 하이픈으로 2~3개 그룹. 예) 110123456789 / 110-123-456789 */
    static final String ACCOUNT_PATTERN = "^[0-9]{6,20}$|^[0-9]{2,6}(-[0-9]{1,8}){1,2}$";

    /** 한글·영문·숫자·공백과 최소한의 문장부호만. 제어문자/개행 금지(로그 인젝션 방지). */
    static final String REMITTANCE_PATTERN = "^[0-9A-Za-z가-힣ㄱ-ㅎㅏ-ㅣ ()._,\\-]*$";
}
