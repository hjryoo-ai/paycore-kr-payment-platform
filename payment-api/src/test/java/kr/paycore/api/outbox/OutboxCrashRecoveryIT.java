package kr.paycore.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.paycore.api.support.AbstractPaymentApiIT;
import kr.paycore.api.support.KafkaTestConsumer;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.outbox.OutboxPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * 장애 시나리오 #6 — 발행 직전 크래시 (docs §8).
 *
 * <p>재현: 상태 전이 + OUTBOX INSERT 는 커밋됐지만 폴러가 돌기 전에 프로세스가 죽는다.
 * 이 테스트는 폴러 주기를 사실상 무한(24h)으로 두어 "폴러가 한 번도 돌지 않은 상태"를 만든다.
 * 그런 다음 폴러를 직접 호출하는 것이 곧 <b>재기동</b>에 해당한다.
 *
 * <p>합격 기준: 유실 0. 크래시 시점에 이미 커밋된 이벤트는 재기동 후 빠짐없이 발행된다.
 */
@TestPropertySource(
        properties = {
            // 컨텍스트 기동 직후 1회만 돌고 그 뒤로는 사실상 돌지 않는다 -> '크래시로 폴러가 멈춘 상태'
            "paycore.core.outbox-poll-interval=24h",
            // 스위퍼도 멈춰 둔다. 검증 자체는 접수 커밋 후 리스너가 동기적으로 수행한다.
            "paycore.core.sweep-interval=24h"
        })
class OutboxCrashRecoveryIT extends AbstractPaymentApiIT {

    private static final String TOPIC = "payment.events";

    @Autowired
    private OutboxPublisher publisher;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    @DisplayName("커밋 후 발행 전에 죽어도 이벤트는 남아 있고, 재기동 시 폴러가 유실 없이 발행한다")
    void committedEventsSurviveCrashAndArePublishedAfterRestart() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2));

            // 1) 접수 -> 검증 커밋. 상태는 VALIDATED, 아웃박스에는 NEW 가 남는다.
            String paymentId = accept("idem-crash-001");
            await().untilAsserted(() -> assertThat(paymentStatus(paymentId)).isEqualTo("VALIDATED"));
            await().untilAsserted(() -> assertThat(countOutbox("NEW")).isEqualTo(1));

            // 2) 크래시 상태 확인: DB 에는 사실이 남았고 Kafka 에는 아무것도 나가지 않았다.
            assertThat(consumer.drain(Duration.ofSeconds(2)))
                    .as("폴러가 돌지 않았으므로 Kafka 에는 아무것도 없어야 한다")
                    .isEmpty();
            assertThat(countOutbox("PUBLISHED")).isZero();

            // 3) 재기동 = 폴러 재개
            int published = publisher.publishPending();

            assertThat(published).isEqualTo(1);
            assertThat(countOutbox("NEW")).isZero();
            assertThat(countOutbox("PUBLISHED")).isEqualTo(1);

            List<ConsumerRecord<String, String>> records = consumer.drain(Duration.ofSeconds(5));
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().key()).isEqualTo(paymentId);
            assertThat(new String(
                            records.getFirst().headers().lastHeader("eventType").value()))
                    .isEqualTo(PaymentEventType.PAYMENT_VALIDATED);
        }
    }

    @Test
    @DisplayName("여러 건이 밀려 있어도 재기동 후 전부 발행된다 — 유실 0")
    void allBackloggedEventsArePublished() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2));

            // 금액을 다르게 준다. 같은 금액을 반복하면 DuplicateSuspected 경고가 추가로 쌓여
            // "밀린 건 수"가 흔들린다 (docs §5.2 — 중복 의심은 차단이 아니라 경고).
            int count = 12;
            for (int i = 0; i < count; i++) {
                accept("idem-backlog-" + i, 1_000_000L + i);
            }
            await().until(() -> countByStatus("VALIDATED") == count);
            assertThat(countOutbox("NEW")).isEqualTo(count);

            assertThat(publisher.publishPending()).isEqualTo(count);

            assertThat(countOutbox("NEW")).isZero();
            assertThat(consumer.drain(Duration.ofSeconds(5))).hasSize(count);
        }
    }

    @Test
    @DisplayName("발행이 두 번 일어나도(at-least-once) 이미 PUBLISHED 인 건은 다시 나가지 않는다")
    void publishedEventsAreNotResent() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2));
            accept("idem-once-001");
            await().untilAsserted(() -> assertThat(countOutbox("NEW")).isEqualTo(1));

            assertThat(publisher.publishPending()).isEqualTo(1);
            assertThat(publisher.publishPending()).as("두 번째 호출은 집을 것이 없다").isZero();

            assertThat(consumer.drain(Duration.ofSeconds(5))).hasSize(1);
        }
    }

    private String accept(String idempotencyKey) {
        return accept(idempotencyKey, 1_500_000L);
    }

    private String accept(String idempotencyKey, long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        String body = """
                {
                  "debtorAccount": "110-123-456789",
                  "creditorAccount": "352-987-654321",
                  "creditorBankCode": "088",
                  "amount": %d,
                  "currency": "KRW",
                  "remittanceInfo": "8월 대금"
                }
                """.formatted(amount);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
        return (String) response.getBody().get("paymentId");
    }
}
