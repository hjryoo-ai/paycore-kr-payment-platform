package kr.paycore.ledger.posting;

import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.messaging.PermanentMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code PaymentCleared} 소비 → 기표 (docs §4.1).
 *
 * <p>이 소비자는 재기동 시 같은 메시지를 다시 받을 수 있다는 전제로 만들어져 있다. 크래시 후
 * 오프셋이 되감기면 그대로 재소비되고, 그때 분개가 하나 더 생기면 장부가 두 배가 된다(시나리오 #5).
 */
@Component
public class PaymentClearedConsumer {

    public static final String LISTENER_ID = "ledger-payment-events";

    private static final Logger log = LoggerFactory.getLogger(PaymentClearedConsumer.class);

    private final LedgerPostingService posting;
    private final ObjectMapper objectMapper;

    public PaymentClearedConsumer(LedgerPostingService posting, ObjectMapper objectMapper) {
        this.posting = posting;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = "${paycore.core.events-topic:payment.events}",
            groupId = "${paycore.ledger.consumer-group:ledger-service}")
    public void onEvent(
            @Payload String payload,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "eventId", required = false) String eventId,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {

        if (!PaymentEventType.PAYMENT_CLEARED.equals(eventType)) {
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            throw new PermanentMessageException("eventId 헤더가 없는 이벤트 key=" + key);
        }

        PaymentClearedEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentClearedEvent.class);
        } catch (RuntimeException e) {
            // 스키마가 깨진 메시지는 재시도해도 같다. 재시도 없이 DLT 로 보낸다 — 원장에 반영되지 못한
            // 청산 완료 건은 반드시 사람이 봐야 한다(docs §7.5).
            throw new PermanentMessageException("PaymentCleared 역직렬화 실패 eventId=" + eventId, e);
        }
        posting.post(eventId, event);
    }
}
