package kr.paycore.core.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /**
     * 결제 행을 비관적 쓰기 락으로 잡는다.
     *
     * <p>같은 결제를 두 경로(접수 직후 비동기 검증, 방치 건 스위퍼)가 동시에 집을 수 있다. 낙관적 락만으로도
     * 최종 결과는 맞지만, 진 쪽이 한도를 차감했다가 되돌리는 헛일을 하고 예외 로그가 쌓인다. 여기서 먼저
     * 막으면 늦게 온 쪽은 상태만 보고 조용히 물러난다.
     *
     * <p>잠금 순서는 항상 PAYMENT → DAILY_LIMIT 이다. 반대로 잡는 경로를 만들지 말 것(교착 방지).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") String paymentId);

    Optional<Payment> findByEndToEndId(String endToEndId);

    /**
     * endToEndId 로 찾아 잠근다. 청산 응답은 paymentId 가 아니라 endToEndId 로 돌아온다 —
     * 청산망이 아는 유일한 우리 식별자가 그것이기 때문이다(docs §4.3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.endToEndId = :endToEndId")
    Optional<Payment> findByEndToEndIdForUpdate(@Param("endToEndId") String endToEndId);

    /**
     * 기간은 [from, to) 반열림 구간이다. Between 은 양끝 포함이라 경계에서 중복 집계가 생긴다.
     */
    Page<Payment> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to, Pageable pageable);

    Page<Payment> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            PaymentStatus status, Instant from, Instant to, Pageable pageable);

    /**
     * 비즈니스 중복 의심 판정용 (docs §5.2). 같은 (출금계좌, 입금계좌, 금액) 이 시간 창 안에 이미 있었는가.
     *
     * <p>REJECTED 는 제외한다 — 거절된 건은 돈이 나가지 않았으므로 중복의 근거가 되지 않는다.
     */
    @Query("""
            select p from Payment p
             where p.debtorAccount = :debtor
               and p.creditorAccount = :creditor
               and p.amount = :amount
               and p.paymentId <> :excludeId
               and p.status <> kr.paycore.core.domain.PaymentStatus.REJECTED
               and p.createdAt >= :since
             order by p.createdAt desc
            """)
    List<Payment> findRecentSimilar(
            @Param("debtor") String debtor,
            @Param("creditor") String creditor,
            @Param("amount") long amount,
            @Param("excludeId") String excludeId,
            @Param("since") Instant since,
            Pageable pageable);

    /** RECEIVED 로 방치된 건 — 접수 직후 크래시로 검증이 실행되지 못한 경우를 복구한다. */
    List<Payment> findByStatusAndCreatedAtLessThan(PaymentStatus status, Instant before, Pageable pageable);

    /**
     * 특정 상태에 너무 오래 머문 건 (docs §5.3 timeout 감지, inquiry 스케줄러).
     *
     * <p>{@code createdAt} 이 아니라 {@code updatedAt} 기준인 것이 중요하다 — 물어야 할 것은
     * "언제 접수됐나"가 아니라 "이 상태가 된 지 얼마나 됐나"이기 때문이다.
     */
    List<Payment> findByStatusAndUpdatedAtLessThan(PaymentStatus status, Instant before, Pageable pageable);

    List<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /** 대사 대상 추출용. 업무일자 안에 접수된 건 전부를 상태와 무관하게 가져온다 (docs §5.6). */
    List<Payment> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);

    long countByStatus(PaymentStatus status);
}
