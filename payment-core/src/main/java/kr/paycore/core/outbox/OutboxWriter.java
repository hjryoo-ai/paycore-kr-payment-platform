package kr.paycore.core.outbox;

import kr.paycore.common.id.Ids;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 아웃박스 기록기. <b>반드시 호출자의 트랜잭션 안에서</b> 쓰인다 — 상태 변경과 같은 커밋에 묶여야
 * 이 패턴이 성립한다. 그래서 여기에는 {@code @Transactional} 이 없다.
 *
 * <p>payment-core 안에서 {@code KafkaTemplate} 을 직접 호출하는 것은 금지다(CLAUDE.md 불변식 3).
 * 발행 경로는 {@link OutboxPoller} 하나뿐이다.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Ids ids;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Ids ids) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.ids = ids;
    }

    public OutboxEvent append(String aggregateId, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent(
                ids.newEventId(), aggregateId, eventType, objectMapper.writeValueAsString(payload), ids.now());
        return repository.save(event);
    }
}
