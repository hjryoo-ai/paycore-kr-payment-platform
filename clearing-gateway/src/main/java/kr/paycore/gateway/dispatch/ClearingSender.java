package kr.paycore.gateway.dispatch;

import kr.paycore.core.observability.PaymentMetrics;
import kr.paycore.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * 청산망 송신 (docs §5.3).
 *
 * <p>여기서 하는 일은 "이미 DB 에 기록된 메시지를 큐에 넣는 것"뿐이다. 메시지를 만들거나 상태를
 * 바꾸지 않는다 — 그 순서가 뒤집히면 '보낸 것을 모르는 상태'가 만들어진다.
 */
@Component
public class ClearingSender {

    /** 수신 측이 본문 모양을 추측하지 않도록 종류를 헤더로 명시한다. */
    public static final String HEADER_MSG_TYPE = "msgType";

    private static final Logger log = LoggerFactory.getLogger(ClearingSender.class);

    private final JmsTemplate jmsTemplate;
    private final GatewayProperties properties;
    private final PaymentMetrics metrics;

    public ClearingSender(JmsTemplate jmsTemplate, GatewayProperties properties, PaymentMetrics metrics) {
        this.jmsTemplate = jmsTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void send(OutgoingMessage message) {
        jmsTemplate.convertAndSend(properties.requestQueue(), message.payload(), jms -> {
            jms.setStringProperty(HEADER_MSG_TYPE, message.msgType());
            return jms;
        });
        // 송신 건수는 '재송신 0회'를 지표로도 확인할 수 있게 해 준다.
        metrics.clearingMessageSent(message.msgType());
        log.info("{} 송신 msgId={} endToEndId={}", message.msgType(), message.msgId(), message.endToEndId());
    }
}
