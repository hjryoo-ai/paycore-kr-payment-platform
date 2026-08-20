package kr.paycore.recon.source;

import java.time.Instant;

/**
 * 청산망 EOD 파일의 한 줄 — <b>"청산망이 아는 것"</b> (docs §5.6).
 *
 * <p>우리 DB 를 보고 만든 값이 아니라 상대방이 자기 기록으로 만든 값이다. 그래서 대사가 의미를 갖는다.
 */
public record ClearingEodRecord(
        String endToEndId,
        String msgId,
        String txId,
        String debtorAccount,
        String creditorAccount,
        String creditorBank,
        long amount,
        String currency,
        String status,
        String reason,
        Instant processedAt) {

    /** 청산망이 '돈이 나갔다'고 말하는가. */
    public boolean settled() {
        return "ACSC".equals(status);
    }

    public boolean rejected() {
        return "RJCT".equals(status);
    }
}
