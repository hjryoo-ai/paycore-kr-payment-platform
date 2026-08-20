package kr.paycore.core.ops;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, String> {

    List<DeadLetter> findByStatusOrderByReceivedAtAsc(DeadLetterStatus status);

    List<DeadLetter> findByOrderByReceivedAtAsc();

    /** 같은 원본 레코드가 두 번 적재되지 않게 한다 (UNIQUE 제약이 최종 방어선). */
    Optional<DeadLetter> findByOriginalTopicAndOriginalPartitionAndOriginalOffset(
            String originalTopic, Integer originalPartition, Long originalOffset);

    long countByStatus(DeadLetterStatus status);

    /** 특정 이벤트가 워크리스트에 올라왔는지 — 테스트와 운영 조회 양쪽에서 쓴다. */
    List<DeadLetter> findByEventId(String eventId);
}
