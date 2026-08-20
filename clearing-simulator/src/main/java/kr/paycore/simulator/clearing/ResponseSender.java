package kr.paycore.simulator.clearing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/**
 * pacs.002 송신 담당. {@code OUT_OF_ORDER} 모드의 버퍼도 여기에 있다.
 *
 * <p>버퍼는 무한정 붙들지 않는다 — 정해진 건수가 모이거나 {@code outOfOrderMaxHold} 가 지나면 비운다.
 * "순서를 뒤집는다"가 "영원히 안 보낸다"가 되면 그건 다른 장애를 시뮬레이션하는 것이다.
 */
@Component
public class ResponseSender {

    private static final Logger log = LoggerFactory.getLogger(ResponseSender.class);
    static final String HEADER_MSG_TYPE = "msgType";

    private final JmsTemplate jmsTemplate;
    private final ClearingMessageCodec codec;
    private final SimulatorProperties properties;

    private final Object bufferLock = new Object();
    private final List<Pacs002> buffer = new ArrayList<>();

    public ResponseSender(JmsTemplate jmsTemplate, ClearingMessageCodec codec, SimulatorProperties properties) {
        this.jmsTemplate = jmsTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    /** 즉시 송신. */
    public void send(Pacs002 response) {
        String payload = codec.encode(response);
        jmsTemplate.convertAndSend(properties.responseQueue(), payload, message -> {
            message.setStringProperty(HEADER_MSG_TYPE, ClearingMsgType.PACS_002);
            return message;
        });
        log.info(
                "pacs.002 송신 msgId={} endToEndId={} sts={} rsn={}",
                response.msgId(),
                response.endToEndId(),
                response.status(),
                response.txInfAndSts().stsRsn());
    }

    /** 버퍼에 넣고, 정해진 건수가 차면 <b>역순</b>으로 한꺼번에 내보낸다. */
    public void sendOutOfOrder(Pacs002 response, int batchSize) {
        List<Pacs002> toSend = null;
        synchronized (bufferLock) {
            buffer.add(response);
            if (buffer.size() >= batchSize) {
                toSend = drainReversedLocked();
            }
        }
        if (toSend != null) {
            toSend.forEach(this::send);
        }
    }

    /** 보류 중인 응답을 역순으로 모두 내보낸다. 모드 변경·리셋·최대 보류시간 경과 시 호출된다. */
    public int flushPending() {
        List<Pacs002> toSend;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return 0;
            }
            toSend = drainReversedLocked();
        }
        toSend.forEach(this::send);
        return toSend.size();
    }

    private List<Pacs002> drainReversedLocked() {
        List<Pacs002> snapshot = new ArrayList<>(buffer);
        buffer.clear();
        Collections.reverse(snapshot);
        return snapshot;
    }

    public java.time.Duration maxHold() {
        return properties.outOfOrderMaxHold();
    }
}
