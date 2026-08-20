package kr.paycore.core.process;

import java.time.Clock;
import java.util.List;
import kr.paycore.core.config.PaymentCoreProperties;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RECEIVED 상태로 방치된 결제를 다시 검증한다.
 *
 * <p>왜 필요한가: 접수는 커밋됐는데 검증 시작 전에 프로세스가 죽으면, 그 결제는 아무도 건드리지 않는
 * 고아가 된다. 아웃박스는 "커밋된 이벤트가 발행되는 것"을 보장하지만, "커밋 이후에 시작될 작업"까지
 * 보장하지는 않는다. 그 틈을 메우는 것이 이 스위퍼다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.core", name = "sweeper-enabled", havingValue = "true", matchIfMissing = true)
public class StuckPaymentSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckPaymentSweeper.class);
    private static final int BATCH = 50;

    private final PaymentRepository payments;
    private final PaymentProcessingService processing;
    private final PaymentCoreProperties properties;
    private final Clock clock;

    public StuckPaymentSweeper(
            PaymentRepository payments,
            PaymentProcessingService processing,
            PaymentCoreProperties properties,
            Clock clock) {
        this.payments = payments;
        this.processing = processing;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${paycore.core.sweep-interval:15s}")
    public void sweep() {
        try {
            int recovered = recoverStuck();
            if (recovered > 0) {
                log.warn("RECEIVED 방치 건 재처리 {}건 — 접수 직후 프로세스 중단이 있었을 가능성이 있다", recovered);
            }
        } catch (RuntimeException e) {
            log.error("스위퍼 실패 — 다음 주기에 재시도한다", e);
        }
    }

    /** 테스트에서 직접 호출한다. 각 건은 자기 트랜잭션에서 처리되므로 한 건의 실패가 나머지를 막지 않는다. */
    public int recoverStuck() {
        List<Payment> stuck = findStuck();
        int recovered = 0;
        for (Payment payment : stuck) {
            try {
                processing.validate(payment.paymentId());
                recovered++;
            } catch (RuntimeException e) {
                log.error("방치 건 재처리 실패 paymentId={}", payment.paymentId(), e);
            }
        }
        return recovered;
    }

    // 자기 호출은 스프링 프록시를 타지 않으므로 여기에 @Transactional 을 붙여도 효과가 없다.
    // 단건 조회라 리포지토리 자체 트랜잭션으로 충분하다.
    private List<Payment> findStuck() {
        return payments.findByStatusAndCreatedAtLessThan(
                PaymentStatus.RECEIVED, clock.instant().minus(properties.stuckAfter()), PageRequest.of(0, BATCH));
    }
}
