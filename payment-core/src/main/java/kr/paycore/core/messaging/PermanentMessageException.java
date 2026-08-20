package kr.paycore.core.messaging;

/**
 * 재시도해도 결과가 같은 메시지 오류 (docs §7.5).
 *
 * <p>일시 오류와 영구 오류를 구분하는 것이 poison message 방어의 전부다. 깨진 payload 를
 * DB 커넥션 오류와 똑같이 재시도하면, 그 메시지 하나가 파티션을 영원히 막고 뒤에 있는
 * 정상 결제들이 함께 멈춘다.
 *
 * <p>이 예외는 재시도 없이 곧바로 DLT 로 간다.
 */
public class PermanentMessageException extends RuntimeException {

    public PermanentMessageException(String message) {
        super(message);
    }

    public PermanentMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
