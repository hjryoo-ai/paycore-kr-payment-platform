package kr.paycore.simulator.mode;

import java.util.concurrent.atomic.AtomicReference;
import kr.paycore.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 현재 모드 보관소. 운영 API 와 리스너 스레드가 동시에 접근하므로 원자적으로 교체한다. */
@Component
public class ModeState {

    private static final Logger log = LoggerFactory.getLogger(ModeState.class);

    private final AtomicReference<ModeSettings> current;
    private final SimulatorProperties properties;

    public ModeState(SimulatorProperties properties) {
        this.properties = properties;
        this.current =
                new AtomicReference<>(ModeSettings.normal(properties.defaultDelay(), properties.outOfOrderBatch()));
    }

    public ModeSettings current() {
        return current.get();
    }

    public ModeSettings apply(ModeSettings next) {
        ModeSettings previous = current.getAndSet(next);
        log.warn(
                "시뮬레이터 모드 변경 {} -> {} (delay={}, rejectReason={})",
                previous.mode(),
                next.mode(),
                next.delay(),
                next.rejectReason());
        return previous;
    }

    public void reset() {
        apply(ModeSettings.normal(properties.defaultDelay(), properties.outOfOrderBatch()));
    }
}
