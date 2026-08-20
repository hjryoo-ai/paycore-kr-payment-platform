package kr.paycore.recon.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대사 배치 설정 (docs §5.6).
 *
 * @param simulatorBaseUrl 청산망 EOD 파일을 가져올 곳. 볼륨 공유 없이 HTTP 로도 받을 수 있게 한다
 * @param eodDir EOD CSV 를 찾거나 받아 두는 디렉터리
 * @param reportDir 대사 요약 리포트(md) 출력 디렉터리
 * @param zone 업무일자 계산 기준 시간대 (ADR-0010)
 * @param suspenseAccount 청산미결제 계정 — 원장 대사에서 대변 쪽으로 기대하는 계정이다
 * @param fetchTimeout EOD 다운로드 한도
 */
@ConfigurationProperties(prefix = "paycore.recon")
public record ReconProperties(
        String simulatorBaseUrl,
        String eodDir,
        String reportDir,
        String zone,
        String suspenseAccount,
        Duration fetchTimeout) {

    public ReconProperties {
        simulatorBaseUrl = blankTo(simulatorBaseUrl, "http://localhost:8083");
        eodDir = blankTo(eodDir, "./data/eod");
        reportDir = blankTo(reportDir, "./data/recon");
        zone = blankTo(zone, "Asia/Seoul");
        suspenseAccount = blankTo(suspenseAccount, "CLEARING_SUSPENSE");
        fetchTimeout = fetchTimeout == null ? Duration.ofSeconds(10) : fetchTimeout;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
