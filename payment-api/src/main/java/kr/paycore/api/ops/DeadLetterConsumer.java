package kr.paycore.api.ops;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import kr.paycore.common.id.Ids;
import kr.paycore.core.observability.PaymentMetrics;
import kr.paycore.core.ops.DeadLetter;
import kr.paycore.core.ops.DeadLetterRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLT 를 소비해 운영자 워크리스트에 적재한다 (docs §7.5, §5.7).
 *
 * <p>토픽에만 두면 아무도 보지 않는다. 실패한 메시지는 <b>보이는 곳</b>에 있어야 처리된다.
 *
 * <p>여기서는 <b>절대 비즈니스 로직을 실행하지 않는다</b>. DLT 소비자가 "한 번 더 해보는" 순간
 * 자동 재주입이 되고, 그건 §7.5 가 금지하는 것이다. 재발행은 사람이 원인을 확인한 뒤에만 일어난다.
 */
@Component
public class DeadLetterConsumer {

    public static final String LISTENER_ID = "payment-api-dlt";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    private final DeadLetterRepository deadLetters;
    private final PaymentMetrics metrics;
    private final Ids ids;
    private final Clock clock;

    public DeadLetterConsumer(DeadLetterRepository deadLetters, PaymentMetrics metrics, Ids ids, Clock clock) {
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.ids = ids;
        this.clock = clock;
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${paycore.core.events-topic:payment.events}${paycore.core.retry.dlt-suffix:.DLT}",
            groupId = "${paycore.api.dlt-consumer-group:payment-api-dlt}")
    @Transactional
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic());
        Integer partition = record.partition();
        Long offset = record.offset();

        // 같은 원본이 두 번 밀려나도 워크리스트는 한 줄이다. 제약 위반이 트랜잭션을 오염시키지 않도록
        // 먼저 확인한다 — inbox 와 같은 이유다.
        if (deadLetters
                .findByOriginalTopicAndOriginalPartitionAndOriginalOffset(originalTopic, partition, offset)
                .isPresent()) {
            return;
        }

        DeadLetter entry = new DeadLetter(
                ids.newEventId(),
                originalTopic,
                partition,
                offset,
                record.key(),
                header(record, "eventId", null),
                header(record, "eventType", null),
                record.value() == null ? "" : record.value(),
                rootExceptionType(record),
                truncate(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE, null), 1000),
                clock.instant());
        try {
            deadLetters.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            // 동시 소비로 같은 원본이 겹쳤다. UNIQUE 가 막았으니 그것으로 충분하다.
            log.debug("이미 적재된 DLT 레코드 topic={} partition={} offset={}", originalTopic, partition, offset);
            return;
        }
        metrics.deadLetterReceived(entry.eventType());
        log.error(
                "DLT 적재 — 운영자 확인 필요 deadLetterId={} 원본토픽={} eventType={} 원인={}",
                entry.deadLetterId(),
                originalTopic,
                entry.eventType(),
                entry.exceptionType());
    }

    /**
     * 실제 원인 예외 타입.
     *
     * <p>스프링이 리스너 예외를 {@code ListenerExecutionFailedException} 으로 감싸기 때문에,
     * 최상위 FQCN 만 적으면 워크리스트의 모든 항목이 같은 이름으로 보인다 — 그러면 운영자가
     * 무엇이 잘못됐는지 구분할 수 없다. 원인이 있으면 원인을 적는다.
     */
    private static String rootExceptionType(ConsumerRecord<String, String> record) {
        String cause = header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, null);
        return cause != null ? cause : header(record, KafkaHeaders.DLT_EXCEPTION_FQCN, null);
    }

    private static String header(ConsumerRecord<String, String> record, String name, String fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null ? fallback : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
