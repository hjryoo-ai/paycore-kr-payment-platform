package kr.paycore.api.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.paycore.api.support.AbstractPaymentApiIT;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.ops.DeadLetter;
import kr.paycore.core.ops.DeadLetterRepository;
import kr.paycore.core.ops.DeadLetterStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 시나리오 #7 — poison message (docs §8, §7.5).
 *
 * <p>검증하는 것은 두 가지다. 깨진 메시지가 <b>DLT 로 밀려나는가</b>, 그리고 그 뒤에 있던
 * <b>정상 메시지가 계속 흐르는가</b>. 후자가 더 중요하다 — poison message 의 진짜 피해는
 * 그 메시지 하나가 아니라 뒤에 줄 선 정상 결제들이 함께 멈추는 것이다.
 */
class PoisonMessageDltIT extends AbstractPaymentApiIT {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeadLetterRepository deadLetters;

    @Value("${paycore.core.events-topic}")
    private String eventsTopic;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    private void publish(String key, String eventType, String eventId, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(eventsTopic, key, payload);
        if (eventType != null) {
            record.headers().add(new RecordHeader("eventType", eventType.getBytes()));
        }
        if (eventId != null) {
            record.headers().add(new RecordHeader("eventId", eventId.getBytes()));
        }
        kafkaTemplate.send(record);
    }

    @Test
    @DisplayName("#7 깨진 payload 는 DLT 워크리스트로 가고, 뒤이은 정상 메시지는 그대로 처리된다")
    void poisonMessageGoesToDltAndTrafficKeepsFlowing() {
        // 같은 키를 써서 같은 파티션에 줄 세운다 — poison 이 뒤엣것을 막는지 보려면 이래야 한다.
        String key = "01M0F00000000000000000POIS";
        publish(key, PaymentEventType.PAYMENT_SETTLED, "evt-poison", "{이건 JSON 이 아니다");
        publish(
                key,
                PaymentEventType.PAYMENT_SETTLED,
                "evt-after",
                "{\"paymentId\":\"01M0F00000000000000000AFTR\","
                        + "\"endToEndId\":\"PC-AFTER\",\"journalId\":\"J1\",\"amount\":1000,\"currency\":\"KRW\","
                        + "\"occurredAt\":\"2026-08-20T09:00:00Z\"}");

        await().until(() -> !deadLetters.findByEventId("evt-poison").isEmpty());

        DeadLetter entry = deadLetters.findByEventId("evt-poison").getFirst();
        assertThat(entry.payload()).isEqualTo("{이건 JSON 이 아니다");
        // 스프링이 리스너 예외를 감싸므로 원인 예외를 적어야 운영자가 유형을 구분할 수 있다.
        assertThat(entry.exceptionType()).contains("PermanentMessageException");
        assertThat(entry.originalTopic()).isEqualTo(eventsTopic);
        assertThat(entry.status()).isEqualTo(DeadLetterStatus.NEW);

        // 같은 파티션의 뒤엣것은 막히지 않고 처리됐다 — poison message 의 진짜 피해는 뒤에 줄 선
        // 정상 결제들이 함께 멈추는 것이다. 그 증거는 inbox 에 그 eventId 가 선점된 것이다.
        await().until(() -> processedMessageExists("evt-after"));
        assertThat(deadLetters.findByEventId("evt-after"))
                .as("정상 메시지는 DLT 로 가지 않는다")
                .isEmpty();
    }

    @Test
    @DisplayName("eventId 헤더가 없는 메시지도 DLT 로 간다 — 멱등성을 보장할 수 없으면 처리하지 않는다")
    void messageWithoutEventIdGoesToDlt() {
        String key = "01M0F00000000000000000NOID";
        publish(key, PaymentEventType.PAYMENT_SETTLED, null, "{}");

        await().until(() ->
                deadLetters.findByOrderByReceivedAtAsc().stream().anyMatch(entry -> key.equals(entry.messageKey())));

        assertThat(deadLetters.findByOrderByReceivedAtAsc().stream()
                        .filter(entry -> key.equals(entry.messageKey()))
                        .findFirst()
                        .orElseThrow())
                .satisfies(entry -> {
                    assertThat(entry.exceptionMessage()).contains("eventId");
                    assertThat(entry.eventId()).isNull();
                });
    }

    @Test
    @DisplayName("같은 DLT 레코드를 두 번 적재하지 않는다")
    void deadLetterIsRecordedOnce() {
        publish("01M0F00000000000000000ONCE", PaymentEventType.PAYMENT_SETTLED, "evt-once", "깨진 payload");

        await().until(() -> deadLetters.findByEventId("evt-once").size() == 1);
        // 잠시 더 흘려보내도 늘지 않는다. 재시도가 DLT 를 여러 줄로 만들면 워크리스트가 노이즈가 된다.
        await().during(java.time.Duration.ofSeconds(2))
                .until(() -> deadLetters.findByEventId("evt-once").size() == 1);
    }

    @Test
    @DisplayName("운영자가 DLT 를 재발행하면 감사 기록이 남고 상태가 REPUBLISHED 가 된다")
    void republishIsAudited() {
        publish("01M0F0000000000000000REPUB", PaymentEventType.PAYMENT_SETTLED, "evt-republish", "깨진 payload");
        await().until(() -> !deadLetters.findByEventId("evt-republish").isEmpty());
        String deadLetterId =
                deadLetters.findByEventId("evt-republish").getFirst().deadLetterId();

        var response = rest.exchange(
                "/api/v1/ops/dead-letters/" + deadLetterId + "/republish",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("reason", "원인 확인 후 재처리"), operatorHeaders("kim.ops")),
                OpsController.DeadLetterView.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().status()).isEqualTo(DeadLetterStatus.REPUBLISHED.name());

        var audit = rest.getForObject(
                "/api/v1/ops/audit?targetType=DEAD_LETTER&targetId=" + deadLetterId, OpsController.AuditView[].class);
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.actor()).isEqualTo("kim.ops");
            assertThat(a.action()).isEqualTo(OpsService.ACTION_REPUBLISH);
            assertThat(a.detail()).contains("원인 확인 후 재처리");
        });
    }

    @Test
    @DisplayName("운영자 헤더 없이는 재발행할 수 없다 — 익명 개입을 허용하면 감사 로그가 무의미해진다")
    void republishRequiresOperatorHeader() {
        publish("01M0F000000000000000ANONYM", PaymentEventType.PAYMENT_SETTLED, "evt-anon", "깨진 payload");
        await().until(() -> !deadLetters.findByEventId("evt-anon").isEmpty());
        String deadLetterId = deadLetters.findByEventId("evt-anon").getFirst().deadLetterId();

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var response = rest.exchange(
                "/api/v1/ops/dead-letters/" + deadLetterId + "/republish",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("reason", "무단 재처리 시도"), headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(deadLetters.findById(deadLetterId).orElseThrow().status()).isEqualTo(DeadLetterStatus.NEW);
    }

    private org.springframework.http.HttpHeaders operatorHeaders(String operator) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Operator", operator);
        return headers;
    }

    private boolean processedMessageExists(String messageId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PROCESSED_MESSAGE WHERE MESSAGE_ID = ?", Long.class, messageId);
        return n != null && n > 0;
    }
}
