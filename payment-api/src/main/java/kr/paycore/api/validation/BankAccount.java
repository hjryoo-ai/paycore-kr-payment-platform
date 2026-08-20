package kr.paycore.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 계좌번호의 <b>유효 자릿수</b>를 검사한다.
 *
 * <p>형식 정규식만으로는 부족하다. {@code ^[0-9]{2,6}(-[0-9]{1,8}){1,2}$} 는 "12-3" 같은 4자리
 * 문자열도 통과시키는데, 국내 은행 계좌번호는 10~14자리다. 이런 값이 접수되면 뒤늦게 pacs.008
 * 스키마 검증에서 막혀 결제가 <b>어디에도 못 가고 멈춘다</b> — 접수 시점에 거절하는 편이 정직하다.
 */
@Documented
@Constraint(validatedBy = BankAccountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface BankAccount {

    String message() default "계좌번호 자릿수가 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
