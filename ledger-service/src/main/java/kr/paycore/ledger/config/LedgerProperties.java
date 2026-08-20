package kr.paycore.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 원장 설정 (docs §5.5).
 *
 * @param suspenseAccount 청산미결제 계정. 고객 계좌에서 나간 돈이 청산 완료 전까지 머무는 자리다
 * @param consumerGroup inbox dedup 소비자 그룹 키
 */
@ConfigurationProperties(prefix = "paycore.ledger")
public record LedgerProperties(String suspenseAccount, String consumerGroup) {

    public LedgerProperties {
        suspenseAccount = blankTo(suspenseAccount, "CLEARING_SUSPENSE");
        consumerGroup = blankTo(consumerGroup, "ledger-service");
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
