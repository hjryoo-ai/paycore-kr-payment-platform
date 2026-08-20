package kr.paycore.core.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka 소비 실패 시 재시도/DLT 정책 (docs §7.5).
 *
 * @param maxAttempts 최초 시도를 제외한 재시도 횟수
 * @param initialBackoff 첫 재시도까지의 대기
 * @param backoffMultiplier 재시도마다 대기를 늘리는 배수 — 상대가 회복할 시간을 준다
 * @param maxBackoff 대기 상한. 무한정 늘리면 파티션이 사실상 멈춘다
 * @param dltSuffix DLT 토픽 접미사
 */
@ConfigurationProperties(prefix = "paycore.core.retry")
public record KafkaRetryProperties(
        int maxAttempts, Duration initialBackoff, double backoffMultiplier, Duration maxBackoff, String dltSuffix) {

    public KafkaRetryProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
        backoffMultiplier = backoffMultiplier < 1.0 ? 2.0 : backoffMultiplier;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
        dltSuffix = dltSuffix == null || dltSuffix.isBlank() ? ".DLT" : dltSuffix;
    }
}
