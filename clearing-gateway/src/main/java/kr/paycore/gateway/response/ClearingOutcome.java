package kr.paycore.gateway.response;

import kr.paycore.core.domain.PaymentStatus;

/**
 * pacs.002 한 통을 우리 상태 언어로 번역한 결과.
 *
 * @param target 전이할 목표 상태. {@code null} 이면 <b>확정할 수 없으니 전이하지 않는다</b>
 * @param reason 이력·이벤트에 남길 사유
 * @param resendPermitted 재송신이 정책적으로 허용되는가 — 청산망이 '받은 적 없음'을 확인해 준 경우만 true
 */
public record ClearingOutcome(PaymentStatus target, String reasonCode, String reason, boolean resendPermitted) {

    public static ClearingOutcome hold(String reason) {
        return new ClearingOutcome(null, null, reason, false);
    }

    public boolean isDecided() {
        return target != null;
    }
}
