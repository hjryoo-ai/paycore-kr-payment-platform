package kr.paycore.core.intake;

import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.process.PaymentAcceptedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수의 DB 쓰기/읽기만 담당한다.
 *
 * <p>{@link PaymentIntakeService} 와 분리한 이유: INSERT 가 UNIQUE 제약 위반으로 실패하면 그 트랜잭션은
 * rollback-only 가 되어 같은 트랜잭션 안에서 재조회를 할 수 없다. 그래서 "INSERT 시도"와 "재조회"는 반드시
 * 서로 다른 트랜잭션이어야 하고, 프록시를 타려면 별도 빈이어야 한다(자기 호출은 AOP 를 우회한다).
 */
@Component
public class PaymentIntakeStore {

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;
    private final ApplicationEventPublisher events;

    public PaymentIntakeStore(
            PaymentRepository payments, PaymentStatusHistoryRepository histories, ApplicationEventPublisher events) {
        this.payments = payments;
        this.histories = histories;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return payments.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * 결제 접수 + 최초 상태 이력을 하나의 트랜잭션으로 기록한다.
     *
     * <p>UNIQUE 제약(IDEMPOTENCY_KEY / END_TO_END_ID)이 동시성의 최종 방어선이다. 애플리케이션의 선조회는
     * 최적화일 뿐, 정확성은 DB 제약이 보장한다(docs §6 설계 노트).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment insert(Payment payment, String triggeredBy) {
        Payment saved = payments.saveAndFlush(payment);
        histories.save(new PaymentStatusHistory(
                saved.paymentId(), null, PaymentStatus.RECEIVED, triggeredBy, "채널 접수", saved.createdAt()));
        // AFTER_COMMIT 리스너가 검증을 시작한다. 커밋 전에는 아무 일도 일어나지 않는다.
        events.publishEvent(new PaymentAcceptedEvent(saved.paymentId()));
        return saved;
    }
}
