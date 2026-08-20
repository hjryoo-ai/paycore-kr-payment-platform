package kr.paycore.common.clearing;

/**
 * pacs.002 거래 상태 코드 (ISO 20022 ExternalPaymentTransactionStatus 축약).
 *
 * <p>{@link #ACSP}/{@link #PDNG} 는 <b>"모른다"</b>이지 "실패"가 아니다. 이 구분이 §7.3 의 핵심이다 —
 * 확정되지 않은 응답으로 상태를 확정하면 그 순간 이중 지급 또는 오지급이 만들어진다.
 */
public enum TxSts {
    /** AcceptedSettlementCompleted — 결제 완료. 돈이 나갔다. */
    ACSC,
    /** AcceptedSettlementInProcess — 접수됐고 처리 중. 아직 확정 아님. */
    ACSP,
    /** Pending — 보류. 아직 확정 아님. */
    PDNG,
    /** Rejected — 거절. 돈이 나가지 않았다. */
    RJCT;

    /** 상태를 확정지어도 되는 응답인가. */
    public boolean isFinal() {
        return this == ACSC || this == RJCT;
    }
}
