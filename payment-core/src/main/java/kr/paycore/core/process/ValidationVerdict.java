package kr.paycore.core.process;

/**
 * 비즈니스 검증 결과 (docs §5.2).
 *
 * @param accepted 청산망으로 보낼 수 있는가
 * @param reasonCode 거절 사유 코드 (accepted=true 면 null)
 * @param reason 사람이 읽는 사유
 * @param duplicateOfPaymentId 중복 의심 상대 건 — <b>거절 사유가 아니라 경고</b>다 (docs §5.2)
 */
public record ValidationVerdict(boolean accepted, String reasonCode, String reason, String duplicateOfPaymentId) {

    public static ValidationVerdict accept(String duplicateOfPaymentId) {
        return new ValidationVerdict(true, null, null, duplicateOfPaymentId);
    }

    public static ValidationVerdict reject(String reasonCode, String reason) {
        return new ValidationVerdict(false, reasonCode, reason, null);
    }

    public boolean duplicateSuspected() {
        return duplicateOfPaymentId != null;
    }
}
