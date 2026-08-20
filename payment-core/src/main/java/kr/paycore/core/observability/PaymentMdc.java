package kr.paycore.core.observability;

import org.slf4j.MDC;

/**
 * 결제 1건의 전 구간 로그를 한 번에 찾기 위한 MDC (docs §10.3).
 *
 * <p>이 시스템의 로그는 여러 프로세스·여러 스레드·여러 시각에 흩어진다. 접수는 payment-api 가,
 * 송신은 clearing-gateway 스케줄러가, 기표는 ledger-service 소비자가 남긴다. 사고가 났을 때
 * 그걸 시간순으로 꿰려면 <b>모든 줄에 같은 키</b>가 있어야 한다. 그 키가 endToEndId 다 —
 * paymentId 와 달리 청산망 메시지에도 실려 나가므로 우리 로그와 상대 로그를 잇는 지점이기도 하다.
 *
 * <p>try-with-resources 로만 쓴다. 풀에서 재사용되는 스레드에 MDC 를 남기면 <b>다음 결제의 로그에
 * 엉뚱한 endToEndId 가 붙는다</b> — 사고 조사에서 이보다 나쁜 것은 없다.
 */
public final class PaymentMdc {

    public static final String END_TO_END_ID = "endToEndId";
    public static final String PAYMENT_ID = "paymentId";

    private PaymentMdc() {}

    /** 결제 컨텍스트를 연다. 반드시 try-with-resources 로 감쌀 것. */
    public static Scope with(String paymentId, String endToEndId) {
        return new Scope(paymentId, endToEndId);
    }

    /** MDC 를 이전 값으로 정확히 되돌리는 스코프. 중첩 호출에서도 안전하다. */
    public static final class Scope implements AutoCloseable {

        private final String previousPaymentId;
        private final String previousEndToEndId;

        private Scope(String paymentId, String endToEndId) {
            this.previousPaymentId = MDC.get(PAYMENT_ID);
            this.previousEndToEndId = MDC.get(END_TO_END_ID);
            put(PAYMENT_ID, paymentId);
            put(END_TO_END_ID, endToEndId);
        }

        @Override
        public void close() {
            put(PAYMENT_ID, previousPaymentId);
            put(END_TO_END_ID, previousEndToEndId);
        }

        private static void put(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
