package kr.paycore.core.process;

import java.time.Clock;
import java.util.List;
import kr.paycore.core.config.PaymentCoreProperties;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.limit.DailyLimitService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 비즈니스 검증 (docs §5.2). 포맷 검증은 payment-api 가 이미 끝냈다 — 여기서는 <b>업무 규칙</b>만 본다.
 *
 * <p>순서가 중요하다: 한도 차감은 부수효과가 있으므로 거절 사유가 될 수 있는 검사를 먼저 끝낸 뒤에 한다.
 * 중복 의심 판정은 차단하지 않으므로 한도 차감 뒤에 해도 무방하다.
 */
@Component
public class BusinessValidator {

    static final String REASON_LIMIT_EXCEEDED = "DAILY_LIMIT_EXCEEDED";
    static final String REASON_UNSUPPORTED_BANK = "UNSUPPORTED_CREDITOR_BANK";

    private final PaymentRepository payments;
    private final DailyLimitService dailyLimits;
    private final PaymentCoreProperties properties;
    private final ClearingRouter router;
    private final Clock clock;

    public BusinessValidator(
            PaymentRepository payments,
            DailyLimitService dailyLimits,
            PaymentCoreProperties properties,
            ClearingRouter router,
            Clock clock) {
        this.payments = payments;
        this.dailyLimits = dailyLimits;
        this.properties = properties;
        this.router = router;
        this.clock = clock;
    }

    public ValidationVerdict validate(Payment payment) {
        if (!router.isRoutable(payment.creditorBank())) {
            return ValidationVerdict.reject(
                    REASON_UNSUPPORTED_BANK, "청산망 라우팅이 불가능한 수취은행입니다: " + payment.creditorBank());
        }

        if (!dailyLimits.tryConsume(payment.debtorAccount(), payment.amount())) {
            return ValidationVerdict.reject(REASON_LIMIT_EXCEEDED, "출금계좌의 일일 이체 한도를 초과했습니다.");
        }

        return ValidationVerdict.accept(findDuplicateSuspect(payment));
    }

    private String findDuplicateSuspect(Payment payment) {
        List<Payment> similar = payments.findRecentSimilar(
                payment.debtorAccount(),
                payment.creditorAccount(),
                payment.amount(),
                payment.paymentId(),
                clock.instant().minus(properties.duplicateWindow()),
                PageRequest.of(0, 1));
        return similar.isEmpty() ? null : similar.getFirst().paymentId();
    }
}
