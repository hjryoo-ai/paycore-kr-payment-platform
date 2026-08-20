package kr.paycore.simulator.mode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.simulator.clearing.ClearingRequestListener;
import kr.paycore.simulator.clearing.ProcessedTransfer;
import kr.paycore.simulator.clearing.ResponseSender;
import kr.paycore.simulator.clearing.TransferStore;
import kr.paycore.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 장애 주입 운영 API (docs §5.4). 이 API 가 있어야 장애 시나리오를 <b>재현 가능한 테스트</b>로 만들 수 있다.
 */
@RestController
@RequestMapping("/simulator")
public class SimulatorController {

    private static final Logger log = LoggerFactory.getLogger(SimulatorController.class);

    private final ModeState modeState;
    private final TransferStore store;
    private final ResponseSender sender;
    private final JmsListenerEndpointRegistry listenerRegistry;
    private final SimulatorProperties properties;

    public SimulatorController(
            ModeState modeState,
            TransferStore store,
            ResponseSender sender,
            JmsListenerEndpointRegistry listenerRegistry,
            SimulatorProperties properties) {
        this.modeState = modeState;
        this.store = store;
        this.sender = sender;
        this.listenerRegistry = listenerRegistry;
        this.properties = properties;
    }

    /** 모드 변경 요청. {@code delayMillis} 등은 해당 모드에서만 의미가 있다. */
    public record ModeRequest(
            SimulatorMode mode,
            Long delayMillis,
            StsRsn rejectReason,
            @Min(2) Integer outOfOrderBatch) {}

    public record ModeView(
            SimulatorMode mode, long delayMillis, StsRsn rejectReason, int outOfOrderBatch, boolean consuming) {}

    @GetMapping("/mode")
    public ModeView mode() {
        return view(modeState.current());
    }

    @PutMapping("/mode")
    public ModeView setMode(@Valid @RequestBody ModeRequest request) {
        ModeSettings previous = modeState.current();
        ModeSettings next = new ModeSettings(
                request.mode() == null ? previous.mode() : request.mode(),
                request.delayMillis() == null ? previous.delay() : Duration.ofMillis(request.delayMillis()),
                request.rejectReason() == null ? previous.rejectReason() : request.rejectReason(),
                request.outOfOrderBatch() == null ? previous.outOfOrderBatch() : request.outOfOrderBatch());

        // 모드를 벗어날 때 붙들고 있던 응답을 흘려보낸다 — 모드 전환이 메시지 유실이 되면 안 된다.
        if (previous.mode() == SimulatorMode.OUT_OF_ORDER && next.mode() != SimulatorMode.OUT_OF_ORDER) {
            int flushed = sender.flushPending();
            if (flushed > 0) {
                log.warn("OUT_OF_ORDER 종료 — 보류 응답 {}건 송신", flushed);
            }
        }
        modeState.apply(next);
        applyConsumption(next.mode());
        return view(next);
    }

    /** 상태 초기화. 시나리오 사이에 서로 영향을 주지 않게 한다. */
    @PostMapping("/reset")
    public ModeView reset() {
        sender.flushPending();
        store.clear();
        modeState.reset();
        applyConsumption(SimulatorMode.NORMAL);
        return view(modeState.current());
    }

    @GetMapping("/transfers")
    public Map<String, Object> transfers() {
        List<ProcessedTransfer> all = store.all();
        return Map.of("count", all.size(), "transfers", all);
    }

    @GetMapping("/transfers/{endToEndId}")
    public ResponseEntity<ProcessedTransfer> transfer(@PathVariable String endToEndId) {
        return store.find(endToEndId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    /** {@code DOWN} 은 리스너 자체를 세운다 — 메시지는 큐에 쌓인다(ADR-0009). */
    private void applyConsumption(SimulatorMode mode) {
        var container = listenerRegistry.getListenerContainer(ClearingRequestListener.LISTENER_ID);
        if (container == null) {
            log.warn("리스너 컨테이너를 찾지 못했다 id={}", ClearingRequestListener.LISTENER_ID);
            return;
        }
        if (mode == SimulatorMode.DOWN) {
            container.stop();
            log.warn("DOWN — {} 큐 소비 중단", properties.requestQueue());
        } else if (!container.isRunning()) {
            container.start();
            log.warn("복구 — {} 큐 소비 재개", properties.requestQueue());
        }
    }

    private ModeView view(ModeSettings settings) {
        var container = listenerRegistry.getListenerContainer(ClearingRequestListener.LISTENER_ID);
        return new ModeView(
                settings.mode(),
                settings.delay().toMillis(),
                settings.rejectReason(),
                settings.outOfOrderBatch(),
                container != null && container.isRunning());
    }
}
