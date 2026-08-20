package kr.paycore.api.settlement;

import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentSettledEvent;
import kr.paycore.core.messaging.PermanentMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** {@code PaymentSettled} 소비 → {@code SETTLED} 전이 (docs §4.1 마지막 화살표). */
@Component
public class PaymentSettledConsumer {

    public static final String LISTENER_ID = "payment-api-events";

    private static final Logger log = LoggerFactory.getLogger(PaymentSettledConsumer.class);

    private final SettlementService settlement;
    private final ObjectMapper objectMapper;

    public PaymentSettledConsumer(SettlementService settlement, ObjectMapper objectMapper) {
        this.settlement = settlement;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${paycore.core.events-topic:payment.events}",
            groupId = "${paycore.api.consumer-group:payment-api}")
    public void onEvent(
            @Payload String payload,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "eventId", required = false) String eventId,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {

        if (!PaymentEventType.PAYMENT_SETTLED.equals(eventType)) {
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            throw new PermanentMessageException("eventId 헤더가 없는 이벤트 key=" + key);
        }

        PaymentSettledEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentSettledEvent.class);
        } catch (RuntimeException e) {
            throw new PermanentMessageException("PaymentSettled 역직렬화 실패 eventId=" + eventId, e);
        }
        settlement.settle(eventId, event);
    }
}
