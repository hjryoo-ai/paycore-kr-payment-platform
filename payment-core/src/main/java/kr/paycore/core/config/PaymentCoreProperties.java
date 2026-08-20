package kr.paycore.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * payment-core 운영 파라미터 (docs §5.2).
 *
 * <p>주기·배치크기·한도 같은 값을 코드에 박지 않는 이유: 장애 대응 중에 바꿔야 하는 값들이기 때문이다.
 *
 * @param eventsTopic 내부 이벤트 토픽 (파티션 키 = paymentId)
 * @param outboxPollInterval Outbox poller 주기
 * @param outboxBatchSize 한 번에 발행할 이벤트 수
 * @param outboxPublishTimeout Kafka 발행 응답 대기 한도
 * @param defaultDailyLimit DAILY_LIMIT 행이 없을 때 부여할 기본 일일 한도(원)
 * @param duplicateWindow 동일 (출금계좌, 입금계좌, 금액) 재접수를 중복 의심으로 볼 시간 창
 * @param stuckAfter RECEIVED 상태로 이 시간 이상 머문 건을 유실로 보고 재처리한다
 * @param sweepInterval 위 재처리 스캔 주기
 */
@ConfigurationProperties(prefix = "paycore.core")
public record PaymentCoreProperties(
        String eventsTopic,
        Duration outboxPollInterval,
        int outboxBatchSize,
        Duration outboxPublishTimeout,
        long defaultDailyLimit,
        Duration duplicateWindow,
        Duration stuckAfter,
        Duration sweepInterval) {

    public PaymentCoreProperties {
        eventsTopic = blankTo(eventsTopic, "payment.events");
        outboxPollInterval = nullTo(outboxPollInterval, Duration.ofSeconds(5));
        outboxBatchSize = outboxBatchSize <= 0 ? 100 : outboxBatchSize;
        outboxPublishTimeout = nullTo(outboxPublishTimeout, Duration.ofSeconds(10));
        defaultDailyLimit = defaultDailyLimit <= 0 ? 5_000_000_000L : defaultDailyLimit;
        duplicateWindow = nullTo(duplicateWindow, Duration.ofMinutes(5));
        stuckAfter = nullTo(stuckAfter, Duration.ofSeconds(30));
        sweepInterval = nullTo(sweepInterval, Duration.ofSeconds(15));
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Duration nullTo(Duration v, Duration fallback) {
        return v == null ? fallback : v;
    }
}
