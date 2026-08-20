package kr.paycore.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 1건당 금액 상한. 상한값은 설정(paycore.intake.max-amount)에서 온다. */
@Documented
@Constraint(validatedBy = MaxTransferAmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxTransferAmount {

    String message() default "1건당 이체 한도를 초과했습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
