package kr.paycore.core.domain;

/**
 * 결제 생명주기 상태 (docs §4.2).
 *
 * <p>전이 규칙은 {@code PaymentStateMachine} 한 곳에서만 강제한다. 이 enum은 상태의 이름과 종결 여부만 안다.
 */
public enum PaymentStatus {
    /** API 접수 완료. 아직 아무 판단도 하지 않은 상태. */
    RECEIVED,
    /** 한도·계좌·포맷 검증 통과. */
    VALIDATED,
    /** 검증 실패로 종결. */
    REJECTED,
    /** pacs.008 송신 완료, 응답 대기. */
    SENT_TO_CLEARING,
    /** pacs.002 ACSC 수신 — 청산 확정. */
    CLEARED,
    /** pacs.002 RJCT 또는 inquiry로 '미처리' 확정. */
    FAILED,
    /** 응답 timeout. <b>실패가 아니라 "모른다"</b>는 뜻이다 (docs §7.3). */
    UNKNOWN,
    /** inquiry 반복 실패 등으로 사람이 판단해야 하는 상태. */
    MANUAL_REVIEW,
    /** 원장 반영 완료 — 최종 상태. */
    SETTLED;

    /** 더 이상 전이가 없는 종결 상태인가. */
    public boolean isTerminal() {
        return this == REJECTED || this == FAILED || this == SETTLED;
    }
}
