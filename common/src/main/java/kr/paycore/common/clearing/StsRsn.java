package kr.paycore.common.clearing;

/** pacs.002 거절 사유 코드 (ISO 20022 ExternalStatusReason 축약). */
public enum StsRsn {
    /** InsufficientFunds — 잔액 부족. */
    AM04,
    /** ClosedAccountNumber — 해지 계좌. */
    AC04,
    /** BlockedAccount — 거래 정지 계좌. */
    AC06,
    /** Duplication — 같은 endToEndId 를 이미 처리했다. 청산망의 중복 방어(docs §5.4). */
    DUPL,
    /** NoOriginalTransactionReceived — 원거래를 받은 적이 없다. inquiry 가 '미처리'를 확정하는 근거. */
    NOOR,
    /** Narrative — 기타. */
    NARR
}
