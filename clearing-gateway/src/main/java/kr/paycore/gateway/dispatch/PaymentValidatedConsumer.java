package kr.paycore.gateway.dispatch;

import java.util.Optional;
import kr.paycore.common.clearing.ClearingMessageException;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentValidatedEvent;
import kr.paycore.core.messaging.PermanentMessageException;
import kr.paycore.core.observability.PaymentMdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code payment.events} 소비 → pacs.008 송신 (docs §4.1).
 *
 * <p>송신은 {@link ClearingDispatcher#prepare} 가 커밋된 <b>뒤에</b> 한다. 여기서 예외가 나면
 * 오프셋이 커밋되지 않아 재전달되지만, inbox 가 이미 선점되어 있으므로 두 번 보내지는 않는다.
 * 그 경우 그 건은 SENT_TO_CLEARING 으로 남고 timeout → inquiry 경로가 결론을 낸다.
 */
@Component
public class PaymentValidatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentValidatedConsumer.class);

    private final ClearingDispatcher dispatcher;
    private final ClearingSender sender;
    private final ObjectMapper objectMapper;

    public PaymentValidatedConsumer(ClearingDispatcher dispatcher, ClearingSender sender, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            id = "clearing-gateway-events",
            topics = "${paycore.core.events-topic:payment.events}",
            groupId = "${paycore.gateway.consumer-group:clearing-gateway}")
    public void onEvent(
            @Payload String payload,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "eventId", required = false) String eventId,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {

        if (!PaymentEventType.PAYMENT_VALIDATED.equals(eventType)) {
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            // dedup 키가 없으면 멱등성을 보장할 수 없다. 재시도해도 헤더가 생기지 않으므로 영구 오류다.
            throw new PermanentMessageException("eventId 헤더가 없는 이벤트 key=" + key);
        }

        PaymentValidatedEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentValidatedEvent.class);
        } catch (RuntimeException e) {
            // 깨진 payload 는 재시도 대상이 아니다. 던져서 DLT 워크리스트에 올린다 — 조용히 버리면
            // 결제 하나가 사라진 사실을 아무도 모른다.
            throw new PermanentMessageException("PaymentValidated 역직렬화 실패 eventId=" + eventId, e);
        }

        // 준비와 송신을 한 스코프로 묶는다. 송신 로그는 트랜잭션 밖이라 스코프를 여기서 열지 않으면
        // 정작 'pacs.008 을 보냈다'는 가장 중요한 줄에 endToEndId 가 빠진다.
        try (PaymentMdc.Scope scope = PaymentMdc.with(event.paymentId(), event.endToEndId())) {
            dispatch(eventId, event);
        }
    }

    private void dispatch(String eventId, PaymentValidatedEvent event) {
        Optional<OutgoingMessage> prepared;
        try {
            prepared = dispatcher.prepare(eventId, event);
        } catch (ClearingMessageException e) {
            // 우리가 만든 메시지가 스키마를 어겼다. 재시도해도 결과는 같다. 다만 여기서 그냥 버리면
            // 결제가 VALIDATED 로 영원히 남는다 — 아무 스케줄러도 그 상태를 보지 않기 때문이다.
            // 돈은 나가지 않았으므로 확실하게 REJECTED 로 종결시키고 사실을 이벤트로 남긴다.
            // 이 경우는 결제가 종결됐으므로 DLT 로 보내지 않는다 — 사람이 볼 것은 REJECTED 사유다.
            log.error("pacs.008 생성 실패 paymentId={} 원인={}", event.paymentId(), e.getMessage());
            dispatcher.rejectUnsendable(event.paymentId(), "청산 메시지 규격 위반: " + e.getMessage());
            return;
        }
        prepared.ifPresent(sender::send);
    }
}
