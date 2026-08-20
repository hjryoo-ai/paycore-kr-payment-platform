package kr.paycore.gateway.dispatch;

/**
 * 커밋된 뒤에 내보낼 메시지. 트랜잭션 안에서 만들고 <b>커밋 이후에</b> 송신한다 (docs §5.3, ADR-0008).
 *
 * <p>송신을 트랜잭션 안에서 하면, 롤백된 이체가 청산망에 나가버리는 유령 송신이 생긴다.
 */
public record OutgoingMessage(String msgId, String msgType, String endToEndId, String payload) {}
