package kr.paycore.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import kr.paycore.core.domain.PaymentStatus;
import org.springframework.stereotype.Component;

/**
 * 사건이 일어나는 순간 올리는 카운터 (docs §10.3).
 *
 * <p>상태별 <b>건수</b>는 게이지로 주기 조회하지만({@link PaymentMetricsBinder}), <b>전이 자체</b>는
 * 일어난 순간에만 셀 수 있다. 게이지만 보면 "UNKNOWN 이 3건"은 알아도 "오늘 UNKNOWN 을 몇 번
 * 거쳐 갔는가"는 영원히 알 수 없다 — 후자가 downstream 안정성의 실제 지표다.
 */
@Component
public class PaymentMetrics {

    private static final String TRANSITIONS = "paycore.payment.transitions";
    private static final String ACCEPTED = "paycore.payment.accepted";
    private static final String CLEARING_SENT = "paycore.clearing.messages.sent";
    private static final String INQUIRIES = "paycore.clearing.inquiries";
    private static final String DEAD_LETTERS = "paycore.deadletter.received";
    private static final String OPS_ACTIONS = "paycore.ops.actions";

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 접수 TPS 의 원천. */
    public void accepted() {
        Counter.builder(ACCEPTED).description("접수된 결제 건수").register(registry).increment();
    }

    public void transitioned(PaymentStatus from, PaymentStatus to) {
        Counter.builder(TRANSITIONS)
                .description("상태 전이 횟수")
                .tags(Tags.of("from", from.name(), "to", to.name()))
                .register(registry)
                .increment();
    }

    /** 청산망으로 나간 메시지. 재송신이 없다는 것을 지표로도 확인할 수 있어야 한다. */
    public void clearingMessageSent(String msgType) {
        Counter.builder(CLEARING_SENT)
                .description("청산망 송신 메시지 수")
                .tags(Tags.of("msgType", msgType))
                .register(registry)
                .increment();
    }

    public void inquirySent(int attempt) {
        Counter.builder(INQUIRIES)
                .description("pacs.028 상태조회 송신 수")
                .tags(Tags.of("attempt", String.valueOf(attempt)))
                .register(registry)
                .increment();
    }

    public void deadLetterReceived(String eventType) {
        Counter.builder(DEAD_LETTERS)
                .description("DLT 로 밀려난 메시지 수")
                .tags(Tags.of("eventType", eventType == null ? "unknown" : eventType))
                .register(registry)
                .increment();
    }

    /** 운영자 개입. 이 값이 늘면 자동화가 못 하는 일이 늘고 있다는 뜻이다. */
    public void opsAction(String action) {
        Counter.builder(OPS_ACTIONS)
                .description("운영자 개입 횟수")
                .tags(Tags.of("action", action))
                .register(registry)
                .increment();
    }
}
