package kr.paycore.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.paycore.api.config.IntakeValidationProperties;

public class BankCodeValidator implements ConstraintValidator<BankCode, String> {

    private final IntakeValidationProperties properties;

    public BankCodeValidator(IntakeValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null / 형식 오류는 @NotBlank, @Pattern 이 각각 보고한다. 여기서는 화이트리스트만 본다.
        return value == null || properties.allowedBankCodes().contains(value);
    }
}
