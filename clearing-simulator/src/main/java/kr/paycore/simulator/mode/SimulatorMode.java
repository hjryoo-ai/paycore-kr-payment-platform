package kr.paycore.simulator.mode;

/**
 * 장애 주입 모드 (docs §5.4 + ADR-0009).
 *
 * <p>각 모드는 "검증하고 싶은 우리 쪽 방어 로직"과 1:1로 대응한다. 재미로 넣은 것이 하나도 없다.
 */
public enum SimulatorMode {
    /** 즉시 ACSC 응답. happy path. */
    NORMAL,
    /** 지정 시간 뒤 응답. timeout → UNKNOWN → 늦은 응답 도착 처리를 본다. */
    DELAY,
    /** 내부 처리는 하고 응답만 유실. <b>중복 지급 방지의 핵심 시나리오(#2)</b>. */
    PROCESS_BUT_NO_RESPONSE,
    /** RJCT 응답. 실패 전파를 본다. */
    REJECT,
    /** 같은 pacs.002 를 두 번 송신. 소비자 멱등성(#4)을 본다. */
    DUPLICATE_RESPONSE,
    /** 응답을 모아 순서를 뒤집어 송신. 순서 방어를 본다. */
    OUT_OF_ORDER,
    /** 큐에서 소비를 멈춘다. 메시지는 큐에 쌓인다 — 재시도/DLQ/알림을 본다. */
    DOWN,
    /**
     * 이체 지시(pacs.008)를 받고 <b>아무 기록 없이 버린다</b>. 상태조회에는 NOOR 로 답한다.
     * "실제로 미처리"를 재현해 inquiry → FAILED 확정 경로(#3)를 본다 (ADR-0009).
     */
    DROP_REQUEST
}
