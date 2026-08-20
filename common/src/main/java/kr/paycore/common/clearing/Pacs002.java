package kr.paycore.common.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * pacs.002 — 상태 응답 (docs §5.3, 스키마: {@code schemas/pacs.002.json}).
 *
 * <p>원 이체지시(pacs.008)에 대한 응답과 상태조회(pacs.028)에 대한 응답이 같은 메시지 타입이다.
 * 어느 쪽인지는 {@code orgnlMsgNmId} 로 구분한다 — 게이트웨이는 이 값에 따라 다르게 판단한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pacs002(GrpHdr grpHdr, TxInfAndSts txInfAndSts) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrpHdr(String msgId, Instant creDtTm) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TxInfAndSts(
            String orgnlMsgId,
            String orgnlMsgNmId,
            String orgnlEndToEndId,
            String orgnlTxId,
            TxSts txSts,
            StsRsn stsRsn,
            String addtlInf) {}

    public String msgId() {
        return grpHdr.msgId();
    }

    public String endToEndId() {
        return txInfAndSts.orgnlEndToEndId();
    }

    public TxSts status() {
        return txInfAndSts.txSts();
    }

    /** 이 응답이 상태조회(pacs.028)에 대한 답인가. */
    public boolean answersInquiry() {
        return ClearingMsgType.PACS_028.equals(txInfAndSts.orgnlMsgNmId());
    }
}
