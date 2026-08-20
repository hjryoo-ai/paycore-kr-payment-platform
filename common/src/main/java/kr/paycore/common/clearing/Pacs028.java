package kr.paycore.common.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * pacs.028 — 상태 조회 (docs §7.3, 스키마: {@code schemas/pacs.028.json}).
 *
 * <p>timeout 이 났을 때 <b>재송신 대신</b> 보내는 메시지다. "다시 보내줘"가 아니라 "그거 처리했어?"를
 * 묻는다. 이 구분이 중복 지급을 막는 1차 방어선이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pacs028(GrpHdr grpHdr, TxInf txInf) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrpHdr(String msgId, Instant creDtTm) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TxInf(String orgnlMsgId, String orgnlMsgNmId, String orgnlEndToEndId, String orgnlTxId) {}

    public String msgId() {
        return grpHdr.msgId();
    }

    public String endToEndId() {
        return txInf.orgnlEndToEndId();
    }
}
