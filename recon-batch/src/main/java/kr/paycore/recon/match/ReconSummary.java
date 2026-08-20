package kr.paycore.recon.match;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * 대사 1회 실행 결과 요약.
 *
 * @param ourCount 우리가 그날 접수한 결제 수
 * @param clearingCount 청산망 파일의 건수
 * @param ledgerCount 분개 수
 * @param openBreaks 새로 만들어진 OPEN 불일치 수
 * @param breaksByType 유형별 건수 — 운영자가 어디부터 볼지 정하는 데 쓴다
 */
public record ReconSummary(
        LocalDate reconDate,
        int ourCount,
        int clearingCount,
        int ledgerCount,
        int openBreaks,
        Map<String, Integer> breaksByType,
        String reportFile,
        Instant executedAt) {

    public boolean clean() {
        return openBreaks == 0;
    }
}
