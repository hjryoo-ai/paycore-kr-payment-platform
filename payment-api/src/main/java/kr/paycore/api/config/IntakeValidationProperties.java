package kr.paycore.api.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 접수 입력 검증 정책 (docs §5.1 secure coding).
 *
 * <p>은행코드는 <b>화이트리스트</b>다. 블랙리스트는 새 값이 추가될 때마다 조용히 통과하지만, 화이트리스트는
 * 조용히 실패한다 — 돈이 나가는 경로에서는 후자가 옳다.
 *
 * @param allowedBankCodes 허용 수취은행 코드(금융기관 표준코드 3자리)
 * @param maxAmount 1건당 금액 상한(원)
 * @param allowedCurrency 허용 통화 — 이 프로젝트는 원화 이체만 다룬다
 */
@ConfigurationProperties(prefix = "paycore.intake")
public record IntakeValidationProperties(Set<String> allowedBankCodes, long maxAmount, String allowedCurrency) {

    public IntakeValidationProperties {
        allowedBankCodes = allowedBankCodes == null || allowedBankCodes.isEmpty()
                ? Set.of(
                        "002", "003", "004", "007", "011", "020", "023", "027", "031", "032", "034", "035", "037",
                        "039", "045", "048", "071", "081", "088", "089", "090", "092")
                : Set.copyOf(allowedBankCodes);
        maxAmount = maxAmount <= 0 ? 1_000_000_000L : maxAmount;
        allowedCurrency = allowedCurrency == null || allowedCurrency.isBlank() ? "KRW" : allowedCurrency;
    }
}
