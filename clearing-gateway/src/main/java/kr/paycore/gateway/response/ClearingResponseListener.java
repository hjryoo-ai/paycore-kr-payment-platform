package kr.paycore.gateway.response;

import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMessageException;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.gateway.dispatch.ClearingSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** CLR.RES 수신 (docs §5.3). 스키마 검증을 통과한 메시지만 상태 로직으로 내려보낸다. */
@Component
public class ClearingResponseListener {

    public static final String LISTENER_ID = "clearing-response-listener";

    private static final Logger log = LoggerFactory.getLogger(ClearingResponseListener.class);

    private final ClearingMessageCodec codec;
    private final ClearingResponseHandler handler;

    public ClearingResponseListener(ClearingMessageCodec codec, ClearingResponseHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    @JmsListener(id = LISTENER_ID, destination = "${paycore.gateway.response-queue:CLR.RES}")
    public void onMessage(
            @Payload String payload, @Header(name = ClearingSender.HEADER_MSG_TYPE, required = false) String msgType) {
        if (msgType != null && !ClearingMsgType.PACS_002.equals(msgType)) {
            log.error("응답 큐에 예상치 못한 msgType={} — 폐기한다", msgType);
            return;
        }
        Pacs002 response;
        try {
            response = codec.decode(payload, Pacs002.class);
        } catch (ClearingMessageException e) {
            // 스키마 위반은 영구 오류다. 재시도해도 같으므로 여기서 끝낸다(docs §7.5).
            log.error("pacs.002 계약 위반 — 폐기한다 원인={}", e.getMessage());
            return;
        }
        handler.handle(response, payload);
    }
}
