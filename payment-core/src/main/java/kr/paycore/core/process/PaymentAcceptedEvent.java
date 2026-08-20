package kr.paycore.core.process;

/**
 * 프로세스 내부 신호 (Kafka 이벤트가 아니다).
 *
 * <p>접수 트랜잭션이 <b>커밋된 뒤</b> 검증을 시작하기 위한 스프링 애플리케이션 이벤트다. 접수와 검증을
 * 한 트랜잭션으로 묶지 않는 이유: 검증이 실패해 롤백되면 멱등성 레코드까지 사라져, 재시도한 클라이언트가
 * 새 결제를 만들어 버린다.
 */
public record PaymentAcceptedEvent(String paymentId) {}
