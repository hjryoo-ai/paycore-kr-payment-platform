package kr.paycore.simulator.clearing;

import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMessageException;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs008;
import kr.paycore.common.clearing.Pacs028;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * CLR.REQ 수신 (docs §5.4).
 *
 * <p>메시지 종류는 JMS 속성 {@code msgType} 으로 구분한다. 본문 모양을 보고 추측하지 않는 이유:
 * 스키마가 조금만 겹쳐도 오판하고, 결제 메시지에서 오판은 곧 오지급이다.
 *
 * <p>리스너에 {@code id} 를 준 것은 {@code DOWN} 모드에서 컨테이너를 정지/재개하기 위해서다.
 */
@Component
public class ClearingRequestListener {

    public static final String LISTENER_ID = "clearing-request-listener";

    private static final Logger log = LoggerFactory.getLogger(ClearingRequestListener.class);

    private final ClearingMessageCodec codec;
    private final ClearingProcessor processor;

    public ClearingRequestListener(ClearingMessageCodec codec, ClearingProcessor processor) {
        this.codec = codec;
        this.processor = processor;
    }

    @JmsListener(id = LISTENER_ID, destination = "${paycore.simulator.request-queue:CLR.REQ}")
    public void onMessage(
            @Payload String payload, @Header(name = ResponseSender.HEADER_MSG_TYPE, required = false) String msgType) {
        try {
            if (ClearingMsgType.PACS_008.equals(msgType)) {
                processor.onCreditTransfer(codec.decode(payload, Pacs008.class));
            } else if (ClearingMsgType.PACS_028.equals(msgType)) {
                processor.onStatusRequest(codec.decode(payload, Pacs028.class));
            } else {
                // 계약 위반은 영구 오류다. 재시도해도 같은 결과이므로 여기서 끝낸다(docs §7.5).
                log.error("알 수 없는 msgType={} — 폐기한다", msgType);
            }
        } catch (ClearingMessageException e) {
            log.error("청산 메시지 계약 위반 — 폐기한다 msgType={} 원인={}", msgType, e.getMessage());
        }
    }
}
