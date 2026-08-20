package kr.paycore.common.clearing;

/**
 * 청산 메시지 종류 (docs §5.3). 값은 {@code CLEARING_MESSAGE_LOG.MSG_TYPE} 과 JSON 스키마의
 * {@code orgnlMsgNmId} 에 그대로 쓰인다 — 한 곳에서만 정의한다.
 */
public final class ClearingMsgType {

    /** 고객 이체 지시 (FIToFICustomerCreditTransfer). */
    public static final String PACS_008 = "pacs.008";

    /** 상태 응답 (FIToFIPaymentStatusReport). */
    public static final String PACS_002 = "pacs.002";

    /** 상태 조회 (FIToFIPaymentStatusRequest). */
    public static final String PACS_028 = "pacs.028";

    private ClearingMsgType() {}
}
