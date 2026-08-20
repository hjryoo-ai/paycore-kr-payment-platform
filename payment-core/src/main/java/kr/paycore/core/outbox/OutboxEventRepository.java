package kr.paycore.core.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * 발행 대기 이벤트를 <b>행 잠금과 함께</b> 가져온다. 인스턴스가 여러 개여도 같은 이벤트를 두 poller 가
     * 동시에 집지 않도록 {@code SKIP LOCKED} 를 쓴다.
     *
     * <p>Oracle 은 {@code FETCH FIRST ... ROWS ONLY} 와 {@code FOR UPDATE} 를 같은 질의에 함께 쓸 수 없다
     * (ORA-02014). 그래서 "정렬·제한은 서브쿼리에서, 잠금은 바깥 질의에서" 두 단계로 나눴다.
     */
    @Query(value = """
                    SELECT * FROM OUTBOX_EVENT
                     WHERE EVENT_ID IN (
                           SELECT EVENT_ID FROM (
                                  SELECT EVENT_ID FROM OUTBOX_EVENT
                                   WHERE STATUS = 'NEW'
                                   ORDER BY CREATED_AT, EVENT_ID
                                   FETCH FIRST :batchSize ROWS ONLY))
                     ORDER BY CREATED_AT, EVENT_ID
                     FOR UPDATE SKIP LOCKED
                    """, nativeQuery = true)
    List<OutboxEvent> claimPending(@Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);
}
