package kr.paycore.core.event;

import java.time.Instant;

/**
 * 동일 (출금계좌, 입금계좌, 금액) 이 짧은 시간 안에 재접수됨 (docs §5.2).
 *
 * <p><b>차단하지 않는다.</b> 같은 거래처에 같은 금액을 하루 두 번 보내는 것은 정상 업무일 수 있다.
 * 탐지(기술)와 차단(정책)을 분리해 두면, 정책이 바뀌어도 탐지 코드를 건드리지 않는다.
 */
public record DuplicateSuspectedEvent(
        String paymentId, String endToEndId, String priorPaymentId, long amount, Instant occurredAt) {}
