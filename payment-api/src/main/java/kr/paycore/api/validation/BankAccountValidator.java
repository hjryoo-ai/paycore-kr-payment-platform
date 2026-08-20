package kr.paycore.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 구분자를 뺀 숫자 개수가 {@value #MIN_DIGITS}~{@value #MAX_DIGITS} 인지 본다.
 *
 * <p>이 범위는 청산 메시지 스키마({@code schemas/common-defs.json} 의 {@code account})와 <b>짝을 이룬다</b>.
 * 한쪽만 바꾸면 접수는 되는데 송신이 안 되는 결제가 생긴다. {@code AccountContractAlignmentTest} 가
 * 두 값이 어긋나는 순간을 잡는다.
 */
public class BankAccountValidator implements ConstraintValidator<BankAccount, String> {

    /** 국내 은행 계좌번호 최소 자릿수. */
    public static final int MIN_DIGITS = 10;

    /** 최대 자릿수. 컬럼 길이(32)와 구분자를 감안한 상한이다. */
    public static final int MAX_DIGITS = 20;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // 존재 여부는 @NotBlank 의 책임이다.
        }
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                digits++;
            }
        }
        return digits >= MIN_DIGITS && digits <= MAX_DIGITS;
    }
}
