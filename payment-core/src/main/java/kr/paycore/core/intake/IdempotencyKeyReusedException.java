package kr.paycore.core.intake;

/** 같은 Idempotency-Key 로 다른 내용의 요청이 들어왔을 때. 재시도가 아니라 클라이언트 오류다. */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key 가 다른 요청 본문으로 재사용되었습니다.");
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
