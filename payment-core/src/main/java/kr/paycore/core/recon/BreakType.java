package kr.paycore.core.recon;

/**
 * 대사 불일치 유형 (docs §5.6).
 *
 * <p>유형을 나누는 기준은 "누가 무엇을 안다고 주장하는가"다. 그래야 운영자가 어디부터 확인해야 할지
 * 알 수 있다. 단순히 "불일치"라고만 적으면 매번 처음부터 조사해야 한다.
 */
public enum BreakType {
    /** 우리는 돈이 나갔다고 아는데 청산망 파일에 없다. 가장 위험한 쪽 — 유령 지급일 수 있다. */
    MISSING_AT_CLEARING,
    /** 청산망은 처리했는데 우리는 미완료다. 방치된 UNKNOWN 이 여기로 잡힌다 (시나리오 #8). */
    MISSING_AT_US,
    /** 양쪽 다 아는 건인데 금액이 다르다. */
    AMOUNT_MISMATCH,
    /** 결제 상태와 원장이 어긋난다 — 분개 누락, 합계 불일치, 금액 불일치. */
    LEDGER_MISMATCH,
    /** 양쪽 다 아는 건인데 결과(성공/실패)가 다르다 (ADR-0010). */
    STATUS_MISMATCH
}
