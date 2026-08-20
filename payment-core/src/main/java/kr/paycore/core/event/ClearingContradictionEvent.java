package kr.paycore.core.event;

import java.time.Instant;

/**
 * 청산 응답이 이미 확정된 상태와 모순된다 (docs §7.4).
 *
 * <p>예: {@code CLEARED} 로 확정한 뒤 같은 이체에 대한 {@code RJCT} 가 도착. 어느 쪽이 사실인지
 * 시스템은 알 수 없다. <b>자동으로 덮어쓰지 않는다</b> — 덮어쓰면 이미 나간 돈을 실패로 기록하거나
 * 그 반대가 된다. 사실만 남기고 운영자 워크리스트로 올린다.
 *
 * @param currentStatus 모순이 감지된 시점의 우리 상태
 * @param respondedStatus 응답이 주장하는 상태
 * @param escalated MANUAL_REVIEW 로 전이했는가. 종결 상태여서 전이할 수 없으면 false 이고, 그래도 알림은 나간다
 */
public record ClearingContradictionEvent(
        String paymentId,
        String endToEndId,
        String clearingMsgId,
        String currentStatus,
        String respondedStatus,
        String reasonCode,
        boolean escalated,
        Instant occurredAt) {}
