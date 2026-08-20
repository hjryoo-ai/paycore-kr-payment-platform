package kr.paycore.core.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 DB 를 읽어 채우는 게이지 (docs §10.3).
 *
 * <p>게이지 콜백에서 직접 쿼리하지 않는다. Prometheus 가 스크레이프할 때마다 DB 를 때리면
 * 관측이 부하가 되고, 스크레이프가 느려지면 관측 자체가 끊긴다. 대신 정해진 주기로 한 번 읽어
 * {@link AtomicLong} 에 담고 게이지는 그 값을 본다.
 *
 * <p>여기서 재는 것들은 전부 <b>알림 규칙과 짝</b>이다: {@code UNKNOWN 5분 초과}, {@code outbox lag},
 * {@code DLT 적재}. 알림으로 쓰지 않을 값은 재지 않는다 — 아무도 안 보는 지표는 노이즈다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.core", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(PaymentMetricsBinder.class);
    private static final int OLDEST_SCAN = 1;

    private final PaymentRepository payments;
    private final OutboxEventRepository outboxEvents;
    private final Clock clock;

    private final Map<PaymentStatus, AtomicLong> statusCounts = new EnumMap<>(PaymentStatus.class);
    private final AtomicLong oldestUnknownAgeSeconds = new AtomicLong();
    private final AtomicLong oldestManualReviewAgeSeconds = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxLagSeconds = new AtomicLong();

    public PaymentMetricsBinder(
            PaymentRepository payments, OutboxEventRepository outboxEvents, MeterRegistry registry, Clock clock) {
        this.payments = payments;
        this.outboxEvents = outboxEvents;
        this.clock = clock;

        for (PaymentStatus status : PaymentStatus.values()) {
            AtomicLong holder = new AtomicLong();
            statusCounts.put(status, holder);
            Gauge.builder("paycore.payment.count", holder, AtomicLong::get)
                    .description("상태별 결제 건수")
                    .tags(Tags.of("status", status.name()))
                    .register(registry);
        }

        Gauge.builder("paycore.payment.unknown.age.seconds", oldestUnknownAgeSeconds, AtomicLong::get)
                .description("가장 오래된 UNKNOWN 결제의 체류 시간(초). 5분을 넘으면 알림 대상이다.")
                .register(registry);
        Gauge.builder("paycore.payment.manual_review.age.seconds", oldestManualReviewAgeSeconds, AtomicLong::get)
                .description("가장 오래된 MANUAL_REVIEW 결제의 체류 시간(초)")
                .register(registry);
        Gauge.builder("paycore.outbox.pending", outboxPending, AtomicLong::get)
                .description("아직 발행되지 않은 아웃박스 이벤트 수")
                .register(registry);
        Gauge.builder("paycore.outbox.lag.seconds", outboxLagSeconds, AtomicLong::get)
                .description("가장 오래된 미발행 아웃박스 이벤트의 나이(초)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${paycore.core.metrics-refresh-interval:10s}")
    public void refresh() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            // 관측 실패가 결제를 멈추게 해서는 안 된다. 다음 주기에 다시 시도한다.
            log.warn("메트릭 갱신 실패 — 다음 주기에 재시도한다: {}", e.toString());
        }
    }

    /** 테스트에서 직접 호출한다. 주기를 기다리지 않고 값을 확정하기 위해서다. */
    public void refreshNow() {
        for (PaymentStatus status : PaymentStatus.values()) {
            statusCounts.get(status).set(payments.countByStatus(status));
        }
        oldestUnknownAgeSeconds.set(oldestAgeSeconds(PaymentStatus.UNKNOWN));
        oldestManualReviewAgeSeconds.set(oldestAgeSeconds(PaymentStatus.MANUAL_REVIEW));

        outboxPending.set(outboxEvents.countPending());
        Instant oldest = outboxEvents.oldestPendingCreatedAt();
        outboxLagSeconds.set(
                oldest == null ? 0L : Duration.between(oldest, clock.instant()).toSeconds());
    }

    /**
     * 가장 오래된 건의 체류 시간. 평균이 아니라 <b>최댓값</b>을 재는 이유: 방치된 한 건이 문제이지
     * 전체 평균이 문제인 적은 없다. 평균은 한 건이 며칠 묵어도 조용하다.
     */
    private long oldestAgeSeconds(PaymentStatus status) {
        List<Payment> oldest = payments.findByStatusOrderByUpdatedAtAsc(status, PageRequest.of(0, OLDEST_SCAN));
        if (oldest.isEmpty()) {
            return 0L;
        }
        return Duration.between(oldest.getFirst().updatedAt(), clock.instant()).toSeconds();
    }
}
