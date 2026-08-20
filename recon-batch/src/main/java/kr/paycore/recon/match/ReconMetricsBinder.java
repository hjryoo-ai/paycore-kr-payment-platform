package kr.paycore.recon.match;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import kr.paycore.core.recon.BreakStatus;
import kr.paycore.core.recon.ReconBreakRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대사 불일치 게이지 (docs §10.3 — 알림 규칙 {@code recon break OPEN > 0}).
 *
 * <p>대사는 하루에 한 번 도는 배치지만, 그 결과는 <b>닫힐 때까지</b> 계속 보여야 한다.
 * 배치 실행 순간에만 알리면 아침에 뜬 불일치가 저녁까지 방치돼도 아무도 모른다.
 */
@Component
public class ReconMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(ReconMetricsBinder.class);

    private final ReconBreakRepository breaks;
    private final Clock clock;
    private final AtomicLong openToday = new AtomicLong();

    public ReconMetricsBinder(ReconBreakRepository breaks, MeterRegistry registry, Clock clock) {
        this.breaks = breaks;
        this.clock = clock;
        Gauge.builder("paycore.recon.break.open", openToday, AtomicLong::get)
                .description("당일 업무일자의 미해결 대사 불일치 수. 0 이 아니면 알림 대상이다.")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${paycore.core.metrics-refresh-interval:10s}")
    public void refresh() {
        try {
            openToday.set(breaks.countByReconDateAndStatus(LocalDate.now(clock), BreakStatus.OPEN));
        } catch (RuntimeException e) {
            log.warn("대사 게이지 갱신 실패: {}", e.toString());
        }
    }
}
