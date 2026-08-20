package kr.paycore.core.outbox;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import kr.paycore.core.config.PaymentCoreProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아웃박스 한 배치를 실제로 발행하는 트랜잭션 경계.
 *
 * <p>{@link OutboxPoller} 와 <b>별도 빈</b>인 것이 중요하다. 같은 빈 안에서 {@code @Transactional} 메서드를
 * 호출하면 스프링 프록시를 타지 않아 트랜잭션이 시작되지 않고, 그러면 조회해 온 엔티티가 준영속 상태가 되어
 * {@code markPublished()} 가 DB 에 반영되지 않는다. 상태가 NEW 로 남으니 같은 이벤트를 영원히 재발행하게 된다.
 *
 * <p>전달 보장은 at-least-once. 발행 후 커밋 전에 죽으면 재발행되며, 중복은 소비자 멱등성이 흡수한다
 * (docs §7.1, §7.2 / ADR-0007).
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String HEADER_EVENT_ID = "eventId";
    private static final String HEADER_EVENT_TYPE = "eventType";

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentCoreProperties properties;
    private final Clock clock;

    public OutboxPublisher(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentCoreProperties properties,
            Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * NEW 상태 이벤트 한 배치를 발행한다.
     *
     * <p>Kafka 발행을 DB 트랜잭션 안에서 하는 것은 의도적이다 — {@code SKIP LOCKED} 로 잡은 행 잠금을 발행이
     * 끝날 때까지 쥐어야 다중 인스턴스에서 같은 이벤트를 두 번 보내는 창이 좁아진다. 대신 배치 크기와
     * 발행 타임아웃을 작게 유지해 트랜잭션이 길어지지 않게 한다.
     *
     * @return 발행에 성공한 이벤트 수
     */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = repository.claimPending(properties.outboxBatchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            if (publish(event)) {
                published++;
            }
        }
        return published;
    }

    private boolean publish(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(properties.eventsTopic(), event.aggregateId(), event.payload());
        record.headers().add(new RecordHeader(HEADER_EVENT_ID, event.eventId().getBytes()));
        record.headers()
                .add(new RecordHeader(HEADER_EVENT_TYPE, event.eventType().getBytes()));

        try {
            kafkaTemplate.send(record).get(properties.outboxPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished(clock.instant());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            event.markAttemptFailed();
            log.warn("아웃박스 발행 중단 eventId={}", event.eventId());
            return false;
        } catch (Exception e) {
            // 발행 실패는 정상 경로다. 상태를 NEW 로 남겨 다음 주기에 다시 시도한다.
            event.markAttemptFailed();
            log.warn(
                    "아웃박스 발행 실패 eventId={} type={} attempts={} 원인={}",
                    event.eventId(),
                    event.eventType(),
                    event.attempts(),
                    e.toString());
            return false;
        }
    }
}
