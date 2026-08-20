package kr.paycore.common.clearing;

import java.util.List;

/**
 * 청산 메시지가 스키마 계약을 어겼다. <b>영구 오류</b>다 — 재시도해도 같은 결과이므로 즉시 DLQ 로
 * 보내야 한다(docs §7.5, poison message 무한 루프 방지).
 */
public class ClearingMessageException extends RuntimeException {

    private final transient List<String> violations;

    public ClearingMessageException(String message, List<String> violations) {
        super(message + (violations.isEmpty() ? "" : " " + violations));
        this.violations = List.copyOf(violations);
    }

    public ClearingMessageException(String message, Throwable cause) {
        super(message, cause);
        this.violations = List.of();
    }

    public List<String> violations() {
        return violations;
    }
}
