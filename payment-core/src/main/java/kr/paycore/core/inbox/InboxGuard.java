package kr.paycore.core.inbox;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 모든 소비자가 통과해야 하는 관문 (CLAUDE.md 불변식 4 / docs §7.2).
 *
 * <p><b>호출자의 트랜잭션 안에서</b> 실행된다. inbox insert 와 비즈니스 로직이 같은 커밋에 묶여야
 * "처리했다고 기록했는데 실제로는 안 했다" 또는 그 반대가 생기지 않는다. 그래서 여기에도
 * {@code @Transactional} 이 없다 — {@code OutboxWriter} 와 같은 이유다.
 *
 * <p>순서가 중요하다: 먼저 존재를 확인하고, 없을 때만 insert 한다. UNIQUE 위반을 잡아서 처리하는
 * 방식은 쓰지 않는다 — 제약 위반이 나면 그 트랜잭션 전체가 rollback-only 로 오염되어 비즈니스 로직까지
 * 같이 죽는다. 제약은 여기서 <b>최종 방어선</b>일 뿐이고, 위반이 나면 롤백 → 재전달 → 다음 번엔
 * 존재 확인에 걸려 정상적으로 skip 된다.
 */
@Component
public class InboxGuard {

    private static final Logger log = LoggerFactory.getLogger(InboxGuard.class);

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public InboxGuard(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 이 메시지를 지금 처리해도 되는지 선점한다.
     *
     * @return 처음 보는 메시지면 true, 이미 처리된 메시지면 false (호출자는 ack 만 하고 skip)
     */
    public boolean claim(String consumerGroup, String messageId) {
        if (repository.existsById(new ProcessedMessage.Key(consumerGroup, messageId))) {
            log.debug("이미 처리된 메시지 — 건너뜀 group={} messageId={}", consumerGroup, messageId);
            return false;
        }
        repository.save(new ProcessedMessage(consumerGroup, messageId, clock.instant()));
        return true;
    }
}
