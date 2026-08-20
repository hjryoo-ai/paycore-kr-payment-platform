package kr.paycore.api.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import kr.paycore.core.ops.DeadLetterRepository;
import kr.paycore.core.ops.DeadLetterStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLT 워크리스트 게이지 (docs §10.3 — 알림 규칙 {@code DLT > 0}).
 *
 * <p>카운터({@code paycore.deadletter.received})만으로는 "지금 처리되지 않고 남아 있는 것이 몇 건인가"를
 * 알 수 없다. 알림을 걸어야 하는 값은 누적이 아니라 <b>미처리 잔량</b>이다.
 */
@Component
public class DeadLetterMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMetricsBinder.class);

    private final DeadLetterRepository deadLetters;
    private final AtomicLong open = new AtomicLong();

    public DeadLetterMetricsBinder(DeadLetterRepository deadLetters, MeterRegistry registry) {
        this.deadLetters = deadLetters;
        Gauge.builder("paycore.deadletter.open", open, AtomicLong::get)
                .description("아직 운영자가 처리하지 않은 DLT 항목 수. 0 이 아니면 알림 대상이다.")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${paycore.core.metrics-refresh-interval:10s}")
    public void refresh() {
        try {
            open.set(deadLetters.countByStatus(DeadLetterStatus.NEW));
        } catch (RuntimeException e) {
            log.warn("DLT 게이지 갱신 실패: {}", e.toString());
        }
    }
}
