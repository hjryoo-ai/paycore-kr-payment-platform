package kr.paycore.simulator.mode;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import kr.paycore.common.clearing.StsRsn;

/**
 * 현재 모드와 그 파라미터. 불변 record 라 읽는 쪽이 일관된 스냅샷을 본다 —
 * 모드와 지연시간을 따로 읽다가 그 사이에 바뀌면 재현 불가능한 동작이 된다.
 */
public record ModeSettings(
        @NotNull SimulatorMode mode,
        @NotNull Duration delay,
        @NotNull StsRsn rejectReason,
        @Min(2) int outOfOrderBatch) {

    public static ModeSettings normal(Duration defaultDelay, int outOfOrderBatch) {
        return new ModeSettings(SimulatorMode.NORMAL, defaultDelay, StsRsn.AM04, outOfOrderBatch);
    }
}
