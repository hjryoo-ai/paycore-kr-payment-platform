package kr.paycore.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 설정된 화이트리스트에 있는 수취은행 코드인지 검증한다. */
@Documented
@Constraint(validatedBy = BankCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BankCode {

    String message() default "지원하지 않는 수취은행 코드입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
