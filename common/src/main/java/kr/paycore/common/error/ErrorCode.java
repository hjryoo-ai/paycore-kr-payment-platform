package kr.paycore.common.error;

/**
 * 시스템 전역 에러 코드 (docs §10.2 — 오류 응답에 내부 정보를 노출하지 않는다).
 *
 * <p>코드 체계: {@code PC-<영역><번호>} / 영역 = V(검증) B(비즈니스) C(청산) L(원장) S(시스템)
 */
public enum ErrorCode {
    /** 요청 본문/헤더가 스펙을 벗어남. */
    VALIDATION_FAILED("PC-V001", "요청 값이 올바르지 않습니다."),
    /** Idempotency-Key 헤더 누락. */
    IDEMPOTENCY_KEY_REQUIRED("PC-V002", "Idempotency-Key 헤더가 필요합니다."),
    /** 같은 Idempotency-Key로 다른 본문이 들어옴 — 재시도가 아니라 클라이언트 버그. */
    IDEMPOTENCY_KEY_REUSED("PC-V003", "이미 사용된 Idempotency-Key 입니다."),
    /** 조회 대상 없음. */
    PAYMENT_NOT_FOUND("PC-B001", "결제 건을 찾을 수 없습니다."),
    /** 허용되지 않은 상태 전이 시도. */
    ILLEGAL_STATE_TRANSITION("PC-B002", "허용되지 않은 상태 전이입니다."),
    /** 청산 메시지를 규격에 맞게 만들 수 없다 — 재시도해도 같으므로 거절로 종결한다. */
    CLEARING_MESSAGE_INVALID("PC-C001", "청산 메시지를 생성할 수 없습니다."),
    /** 운영자가 요청한 상태 변경이 전이표에 없다. repair 는 예외 통로가 아니다. */
    PAYMENT_NOT_REPAIRABLE("PC-O001", "해당 결제는 이 방식으로 처리할 수 없습니다."),
    /** DLT 항목이 없거나 이미 처리됐다. */
    DEAD_LETTER_NOT_ACTIONABLE("PC-O002", "해당 DLT 항목은 처리할 수 없습니다."),
    /** 그 외. 상세 원인은 로그에만 남긴다. */
    INTERNAL_ERROR("PC-S001", "처리 중 오류가 발생했습니다.");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
