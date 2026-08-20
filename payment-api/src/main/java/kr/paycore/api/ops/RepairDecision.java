package kr.paycore.api.ops;

/**
 * 운영자가 내릴 수 있는 결론 (docs §5.7 repair).
 *
 * <p>{@code UNKNOWN} 이나 {@code MANUAL_REVIEW} 로 되돌리는 선택지는 없다. repair 는
 * <b>사람이 청산망과 확인한 사실</b>을 기록하는 행위이지, 판단을 다시 미루는 행위가 아니다.
 */
public enum RepairDecision {
    /** 청산망에서 지급이 확인됐다. */
    CLEARED,
    /** 청산망에서 미처리가 확인됐다. */
    FAILED
}
