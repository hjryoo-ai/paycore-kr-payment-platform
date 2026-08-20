package kr.paycore.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 플랫폼이 처리하는 통화인지 검증한다. 원화 이체 전용이므로 사실상 KRW 고정이다. */
@Documented
@Constraint(validatedBy = SupportedCurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportedCurrency {

    String message() default "지원하지 않는 통화입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
