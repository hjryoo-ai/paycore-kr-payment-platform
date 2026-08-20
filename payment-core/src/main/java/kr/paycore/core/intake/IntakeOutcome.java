package kr.paycore.core.intake;

import kr.paycore.core.domain.Payment;

/**
 * 접수 결과.
 *
 * @param payment 저장된(또는 이미 존재하던) 결제 건
 * @param responseBody 클라이언트에 그대로 내보낼 JSON 문자열 (FIRST_RESPONSE)
 * @param replayed true 면 신규 생성이 아니라 기존 응답 재생 — HTTP 상태코드와 로깅이 달라진다
 */
public record IntakeOutcome(Payment payment, String responseBody, boolean replayed) {

    public static IntakeOutcome created(Payment payment) {
        return new IntakeOutcome(payment, payment.firstResponse(), false);
    }

    public static IntakeOutcome replayed(Payment payment) {
        return new IntakeOutcome(payment, payment.firstResponse(), true);
    }
}
