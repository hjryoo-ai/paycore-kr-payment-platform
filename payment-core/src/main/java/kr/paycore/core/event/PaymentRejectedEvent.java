package kr.paycore.core.event;

import java.time.Instant;

/** 검증 실패로 종결된 결제. 사유 코드는 운영 대시보드와 대사 리포트에서 그대로 쓰인다. */
public record PaymentRejectedEvent(
        String paymentId, String endToEndId, String reasonCode, String reason, Instant occurredAt) {}
