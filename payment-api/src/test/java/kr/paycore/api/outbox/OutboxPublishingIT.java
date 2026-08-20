package kr.paycore.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.paycore.api.support.AbstractPaymentApiIT;
import kr.paycore.api.support.KafkaTestConsumer;
import kr.paycore.core.event.PaymentEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Transactional Outbox → Kafka 발행 (docs §7.1).
 *
 * <p>확인하는 것: 상태 전이와 OUTBOX INSERT 가 같은 커밋에 묶이고, 발행은 폴러만 한다.
 */
class OutboxPublishingIT extends AbstractPaymentApiIT {

    private static final String TOPIC = "payment.events";

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    @DisplayName("접수된 결제는 VALIDATED 로 전이되고 PaymentValidated 가 paymentId 키로 발행된다")
    void validatedPaymentIsPublishedToKafka() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2)); // 이전 테스트 잔여 소진

            String paymentId = accept("idem-outbox-001", 1_500_000L);

            await().untilAsserted(() -> assertThat(paymentStatus(paymentId)).isEqualTo("VALIDATED"));
            await().untilAsserted(() -> assertThat(countOutbox("PUBLISHED")).isEqualTo(1));
            assertThat(countOutbox("NEW")).isZero();

            List<ConsumerRecord<String, String>> records = consumer.drain(Duration.ofSeconds(3));
            assertThat(records).hasSize(1);
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.key()).as("파티션 키는 paymentId — 같은 결제의 순서를 보장한다").isEqualTo(paymentId);
            assertThat(header(record, "eventType")).isEqualTo(PaymentEventType.PAYMENT_VALIDATED);
            assertThat(record.value()).contains(paymentId).contains("\"amount\":1500000");
        }
    }

    @Test
    @DisplayName("일일 한도를 넘으면 REJECTED 로 전이되고 PaymentRejected 가 발행된다 — 청산망으로 나가지 않는다")
    void limitExceededIsRejected() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2));
            // 기본 일일 한도는 50억. 10억짜리 5건은 통과, 6번째가 초과된다.
            for (int i = 0; i < 5; i++) {
                accept("idem-limit-" + i, 1_000_000_000L);
            }
            String over = accept("idem-limit-over", 1_000_000_000L);

            await().untilAsserted(() -> assertThat(paymentStatus(over)).isEqualTo("REJECTED"));

            List<ConsumerRecord<String, String>> records = consumer.drain(Duration.ofSeconds(3));
            ConsumerRecord<String, String> rejected = records.stream()
                    .filter(r -> r.key().equals(over))
                    .findFirst()
                    .orElseThrow();
            assertThat(header(rejected, "eventType")).isEqualTo(PaymentEventType.PAYMENT_REJECTED);
            assertThat(rejected.value()).contains("DAILY_LIMIT_EXCEEDED");

            Long used = jdbc.queryForObject("SELECT USED_AMOUNT FROM DAILY_LIMIT", Long.class);
            assertThat(used).as("거절된 건은 한도를 차감하지 않는다").isEqualTo(5_000_000_000L);
        }
    }

    @Test
    @DisplayName("동일 (출금/입금/금액) 재접수는 차단하지 않고 DuplicateSuspected 경고만 남긴다")
    void duplicateSuspectIsWarnedNotBlocked() {
        try (KafkaTestConsumer consumer = new KafkaTestConsumer(TOPIC)) {
            consumer.drain(Duration.ofSeconds(2));

            String first = accept("idem-dup-1", 777_000L);
            await().untilAsserted(() -> assertThat(paymentStatus(first)).isEqualTo("VALIDATED"));
            String second = accept("idem-dup-2", 777_000L);
            await().untilAsserted(() -> assertThat(paymentStatus(second)).isEqualTo("VALIDATED"));

            await().untilAsserted(() -> assertThat(countOutbox("NEW")).isZero());
            List<ConsumerRecord<String, String>> records = consumer.drain(Duration.ofSeconds(3));

            assertThat(records.stream().map(r -> header(r, "eventType")).toList())
                    .containsExactlyInAnyOrder(
                            PaymentEventType.PAYMENT_VALIDATED,
                            PaymentEventType.PAYMENT_VALIDATED,
                            PaymentEventType.DUPLICATE_SUSPECTED);
            ConsumerRecord<String, String> warning = records.stream()
                    .filter(r -> PaymentEventType.DUPLICATE_SUSPECTED.equals(header(r, "eventType")))
                    .findFirst()
                    .orElseThrow();
            assertThat(warning.value()).contains(second).contains(first);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }

    protected String accept(String idempotencyKey, long amount) {
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
