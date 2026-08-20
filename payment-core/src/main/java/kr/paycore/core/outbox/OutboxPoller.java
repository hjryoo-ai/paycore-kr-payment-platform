package kr.paycore.core.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 발행 스케줄러 (docs §5.2). 실제 발행은 {@link OutboxPublisher} 가 트랜잭션 안에서 한다.
 *
 * <p>여기서 하는 일은 주기적 호출과 예외 격리뿐이다. 스케줄 메서드가 예외를 밖으로 던지면 이후 주기가
 * 멈추고, 그 순간부터 이벤트가 조용히 쌓이기만 한다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.core", name = "outbox-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxPublisher publisher;

    public OutboxPoller(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${paycore.core.outbox-poll-interval:5s}")
    public void poll() {
        try {
            int published = publisher.publishPending();
            if (published > 0) {
                log.debug("아웃박스 발행 {}건", published);
            }
        } catch (RuntimeException e) {
            log.error("아웃박스 발행 주기 실패 — 다음 주기에 재시도한다", e);
        }
    }
}
