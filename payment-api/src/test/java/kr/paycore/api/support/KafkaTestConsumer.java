package kr.paycore.api.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** 테스트에서 토픽 내용을 직접 확인하기 위한 최소 소비자. 매번 새 group.id 로 처음부터 읽는다. */
public final class KafkaTestConsumer implements AutoCloseable {

    private static final Duration MAX_DRAIN = Duration.ofSeconds(30);
    private static final int MAX_RECORDS = 1_000;

    private final KafkaConsumer<String, String> consumer;

    public KafkaTestConsumer(String topic) {
        this.consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()));
        this.consumer.subscribe(Collections.singletonList(topic));
    }

    /**
     * 지금까지 쌓인 레코드를 모두 읽는다. 빈 poll 이 나오거나 상한에 닿으면 멈춘다.
     *
     * <p>상한을 두는 이유: 발행 측 버그로 같은 이벤트가 계속 재발행되면 "빈 poll" 이 영영 오지 않아
     * 테스트가 무한정 매달린다. 실제로 그렇게 한 번 멈춰 봤고, 그때 원인을 찾는 데 걸린 시간이
     * 이 몇 줄보다 훨씬 비쌌다.
     */
    public List<ConsumerRecord<String, String>> drain(Duration perPoll) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        long deadline = System.nanoTime() + MAX_DRAIN.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(perPoll);
            if (records.isEmpty()) {
                return all;
            }
            records.forEach(all::add);
            if (all.size() > MAX_RECORDS) {
                throw new IllegalStateException(
                        "레코드가 %d건을 넘었다 — 발행 측이 같은 이벤트를 반복 발행하고 있을 가능성이 높다".formatted(MAX_RECORDS));
            }
        }
        throw new IllegalStateException("drain 이 %s 안에 끝나지 않았다 — 발행이 멈추지 않고 있다".formatted(MAX_DRAIN));
    }

    @Override
    public void close() {
        consumer.close();
    }
}
