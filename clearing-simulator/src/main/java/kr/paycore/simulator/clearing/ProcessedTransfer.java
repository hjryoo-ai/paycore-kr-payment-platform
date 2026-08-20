package kr.paycore.simulator.clearing;

import java.time.Instant;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;

/**
 * 청산망(시뮬레이터)이 "우리는 이 이체를 이렇게 처리했다"고 아는 사실.
 *
 * <p>이 기록이 존재하는지 여부가 곧 pacs.028 inquiry 의 답이며, EOD CSV 의 한 줄이다.
 * {@code PROCESS_BUT_NO_RESPONSE} 모드에서 응답은 없어도 <b>이 기록은 남는다</b> — 그래서
 * "응답은 못 받았지만 돈은 나갔다"가 재현된다.
 */
public record ProcessedTransfer(
        String endToEndId,
        String msgId,
        String txId,
        String debtorAccount,
        String creditorAccount,
        String creditorBank,
        long amount,
        String currency,
        TxSts status,
        StsRsn reason,
        Instant processedAt) {}
