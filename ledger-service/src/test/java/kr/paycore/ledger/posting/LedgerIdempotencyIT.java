package kr.paycore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.ledger.support.AbstractLedgerIT;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * 시나리오 #5 (docs §8) — consumer 가 크래시 후 재기동해 <b>이미 처리한 메시지를 다시 소비해도</b>
 * 분개는 정확히 한 벌이어야 한다.
 *
 * <p>방어는 2중이다(docs §7.2). 기술 키(inbox)는 같은 메시지의 재전달을 막고, 비즈니스 키
 * ({@code JOURNAL.PAYMENT_ID} UNIQUE)는 메시지 ID 가 달라도 같은 결제의 재기표를 막는다.
 * 두 방어를 각각 따로 무력화해 보는 것이 이 테스트의 목적이다.
 */
class LedgerIdempotencyIT extends AbstractLedgerIT {

    @Autowired
    private LedgerPostingService posting;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("기술 키 방어: 같은 eventId 를 두 번 받아도 분개는 1벌")
    void inboxBlocksSameMessageTwice() {
        Payment payment = givenClearedPayment(1_100_000L);
        PaymentClearedEvent event = clearedEventFor(payment);
        String eventId = ids.newEventId();

        assertThat(posting.post(eventId, event)).isTrue();
        assertThat(posting.post(eventId, event)).as("두 번째 호출은 아무것도 하지 않는다").isFalse();

        assertThat(journals.count()).isEqualTo(1);
        assertThat(entriesOf(payment.paymentId())).hasSize(2);
        assertThat(outboxOf(payment.paymentId(), PaymentEventType.PAYMENT_SETTLED))
                .hasSize(1);
    }

    @Test
    @DisplayName("비즈니스 키 방어: eventId 가 달라도(재발행) 같은 결제면 분개는 1벌")
    void businessKeyBlocksRepublishedEvent() {
        Payment payment = givenClearedPayment(1_200_000L);

        assertThat(posting.post(ids.newEventId(), clearedEventFor(payment))).isTrue();
        // 아웃박스가 at-least-once 라 같은 사실이 새 eventId 로 다시 나올 수 있다. inbox 는 통과한다.
        assertThat(posting.post(ids.newEventId(), clearedEventFor(payment)))
                .as("inbox 를 통과해도 JOURNAL.PAYMENT_ID 가 막는다")
                .isFalse();

        assertThat(journals.count()).isEqualTo(1);
        assertThat(entriesOf(payment.paymentId())).hasSize(2);
        assertThat(entries.globalImbalance()).isZero();
    }

    @Test
    @DisplayName("#5 consumer 를 세우고 오프셋을 되감아 강제 재소비시켜도 분개는 1벌")
    void survivesForcedReconsumption() {
        Payment first = givenClearedPayment(1_300_001L);
        Payment second = givenClearedPayment(1_300_002L);
        publishCleared(first);
        publishCleared(second);

        awaitCondition().until(() -> journals.count() == 2);
        Map<String, String> journalIdsBefore = journalIdsByPayment();

        // 크래시 재기동을 재현한다: 리스너를 세우고 소비자 그룹 오프셋을 처음으로 되감은 뒤 다시 띄운다.
        var container = listenerRegistry.getListenerContainer(PaymentClearedConsumer.LISTENER_ID);
        assertThat(container).isNotNull();
        container.stop();
        rewindConsumerGroupToBeginning();
        container.start();

        // 되감긴 메시지가 다시 흘러 들어간다. inbox 가 전부 걸러야 한다.
        awaitCondition().until(() -> processedMessageCount() >= 2);

        assertThat(journals.count()).as("재소비돼도 분개는 늘지 않는다").isEqualTo(2);
        assertThat(entries.count()).isEqualTo(4);
        assertThat(journalIdsByPayment()).isEqualTo(journalIdsBefore);
        assertThat(entries.globalImbalance()).isZero();
        assertThat(outboxOf(first.paymentId(), PaymentEventType.PAYMENT_SETTLED))
                .hasSize(1);
        assertThat(outboxOf(second.paymentId(), PaymentEventType.PAYMENT_SETTLED))
                .hasSize(1);
    }

    private Map<String, String> journalIdsByPayment() {
        return journals.findAll().stream().collect(Collectors.toMap(j -> j.paymentId(), j -> j.journalId()));
    }

    /** 소비자 그룹 오프셋을 0 으로 되돌린다. 그룹에 멤버가 없을 때(리스너 정지 상태)만 허용된다. */
    private void rewindConsumerGroupToBeginning() {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kr.paycore.ledger.support.SharedContainers.KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            var description = admin.describeTopics(List.of(eventsTopic))
                    .allTopicNames()
                    .get()
                    .get(eventsTopic);
            Map<TopicPartition, OffsetAndMetadata> rewind = description.partitions().stream()
                    .collect(Collectors.toMap(
                            p -> new TopicPartition(eventsTopic, p.partition()), p -> new OffsetAndMetadata(0L)));
            admin.alterConsumerGroupOffsets(consumerGroup, rewind).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("오프셋 되감기 중단", e);
        } catch (Exception e) {
            throw new IllegalStateException("오프셋 되감기 실패", e);
        }
    }
}
