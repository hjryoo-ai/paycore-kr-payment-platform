package kr.paycore.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.paycore.api.config.IntakeValidationProperties;

public class MaxTransferAmountValidator implements ConstraintValidator<MaxTransferAmount, Long> {

    private final IntakeValidationProperties properties;

    public MaxTransferAmountValidator(IntakeValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(Long value, ConstraintValidatorContext context) {
        return value == null || value <= properties.maxAmount();
    }
}
