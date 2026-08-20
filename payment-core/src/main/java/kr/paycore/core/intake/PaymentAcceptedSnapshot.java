package kr.paycore.core.intake;

import java.time.Instant;

/**
 * 접수 응답의 정본. 이 값을 JSON 으로 직렬화해 {@code PAYMENT.FIRST_RESPONSE} 에 저장하고,
 * 같은 Idempotency-Key 재요청 시 <b>저장된 문자열을 그대로</b> 돌려준다.
 *
 * <p>왜 행에서 다시 만들지 않는가: {@code STATUS} 는 이후 VALIDATED/CLEARED 로 계속 바뀐다. 재구성하면
 * "최초 응답"이 아니라 "지금 상태"를 돌려주게 되어 멱등 응답이 아니게 된다.
 */
public record PaymentAcceptedSnapshot(String paymentId, String endToEndId, String status, Instant acceptedAt) {}
