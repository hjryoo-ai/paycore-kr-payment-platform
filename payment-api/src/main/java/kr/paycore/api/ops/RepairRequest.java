package kr.paycore.api.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * repair 요청.
 *
 * @param decision 확인된 사실
 * @param reason 무엇을 근거로 그렇게 판단했는가. <b>필수다</b> — 근거 없는 상태 변경은 감사에서
 *     설명할 수 없고, 설명할 수 없는 변경은 사고와 구분되지 않는다
 */
public record RepairRequest(
        @NotNull RepairDecision decision,
        @NotBlank @Size(max = 500) String reason) {}
