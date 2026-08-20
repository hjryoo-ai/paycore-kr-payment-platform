package kr.paycore.common.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * pacs.008 — 고객 이체 지시 (docs §5.3, 스키마: {@code schemas/pacs.008.json}).
 *
 * <p>필드 이름은 실제 ISO 20022 축약형을 따른다. 규격 자체는 자체 정의 JSON 이다(README 단순화 선언).
 *
 * <p>{@code grpHdr.msgId} 는 이 메시지 1건의 ID(UETR 역할)이고 재송신하면 새로 발급된다.
 * {@code pmtId.endToEndId} 는 <b>절대 바뀌지 않는다</b> — 청산망이 중복을 판정하는 유일한 기준이기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pacs008(GrpHdr grpHdr, CdtTrfTxInf cdtTrfTxInf) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrpHdr(String msgId, Instant creDtTm, int nbOfTxs, String instgAgt, String instdAgt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdtTrfTxInf(
            PmtId pmtId,
            Money intrBkSttlmAmt,
            String dbtrAcct,
            String dbtrAgt,
            String cdtrAcct,
            String cdtrAgt,
            String rmtInf) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PmtId(String endToEndId, String txId) {}

    public String msgId() {
        return grpHdr.msgId();
    }

    public String endToEndId() {
        return cdtTrfTxInf.pmtId().endToEndId();
    }

    public long amount() {
        return cdtTrfTxInf.intrBkSttlmAmt().value();
    }
}
