package kr.paycore.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import kr.paycore.api.config.IntakeValidationProperties;

public class SupportedCurrencyValidator implements ConstraintValidator<SupportedCurrency, String> {

    private final IntakeValidationProperties properties;

    public SupportedCurrencyValidator(IntakeValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || properties.allowedCurrency().equals(value);
    }
}
