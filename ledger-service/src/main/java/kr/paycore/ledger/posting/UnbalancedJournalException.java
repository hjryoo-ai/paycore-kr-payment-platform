package kr.paycore.ledger.posting;

/**
 * 차변 합과 대변 합이 맞지 않는다.
 *
 * <p>원장에서 이것은 "조금 틀린 상태"가 아니라 <b>있어서는 안 되는 상태</b>다. 저장하고 나중에 고치는
 * 것이 아니라 커밋 자체를 막는다 — 틀린 장부는 없는 장부보다 나쁘다.
 */
public class UnbalancedJournalException extends RuntimeException {

    public UnbalancedJournalException(String paymentId, long imbalance) {
        super("분개 합계가 0 이 아니다 paymentId=" + paymentId + " 불균형=" + imbalance);
    }
}
