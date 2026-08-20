package kr.paycore.core.event;

import java.time.Instant;

/**
 * 검증을 통과해 청산망으로 보낼 준비가 된 결제 (docs §4.1).
 *
 * <p>이벤트는 "무엇을 해라"가 아니라 "무슨 일이 일어났다"를 담는다. clearing-gateway 는 이 사실을 보고
 * 자기 판단으로 pacs.008 을 만든다.
 */
public record PaymentValidatedEvent(
        String paymentId,
        String endToEndId,
        String debtorAccount,
        String creditorAccount,
        String creditorBankCode,
        long amount,
        String currency,
        String remittanceInfo,
        Instant occurredAt) {}
