package kr.paycore.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 청산 게이트웨이 운영 파라미터 (docs §5.3).
 *
 * @param memberId 우리 기관 코드. pacs.008 의 {@code instgAgt}/{@code dbtrAgt} 로 나간다
 * @param requestQueue pacs.008 / pacs.028 송신 큐
 * @param responseQueue pacs.002 수신 큐
 * @param consumerGroup inbox dedup 의 소비자 그룹 키 (Kafka·JMS 공통 접두어)
 * @param responseTimeout 이 시간 안에 pacs.002 가 안 오면 UNKNOWN 으로 본다. <b>실패가 아니다.</b>
 * @param timeoutScanInterval timeout 감지 주기
 * @param inquiryScanInterval inquiry 스케줄 주기
 * @param inquiryBackoff inquiry 재시도 간격. 길이가 곧 최대 시도 횟수다
 * @param dispatchBatch 한 주기에 처리할 최대 건수
 */
@ConfigurationProperties(prefix = "paycore.gateway")
public record GatewayProperties(
        String memberId,
        String requestQueue,
        String responseQueue,
        String consumerGroup,
        Duration responseTimeout,
        Duration timeoutScanInterval,
        Duration inquiryScanInterval,
        List<Duration> inquiryBackoff,
        int dispatchBatch) {

    public GatewayProperties {
        memberId = blankTo(memberId, "020");
        requestQueue = blankTo(requestQueue, "CLR.REQ");
        responseQueue = blankTo(responseQueue, "CLR.RES");
        consumerGroup = blankTo(consumerGroup, "clearing-gateway");
        responseTimeout = nullTo(responseTimeout, Duration.ofSeconds(10));
        timeoutScanInterval = nullTo(timeoutScanInterval, Duration.ofSeconds(5));
        inquiryScanInterval = nullTo(inquiryScanInterval, Duration.ofSeconds(5));
        inquiryBackoff = inquiryBackoff == null || inquiryBackoff.isEmpty()
                ? List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
                : List.copyOf(inquiryBackoff);
        dispatchBatch = dispatchBatch <= 0 ? 100 : dispatchBatch;
    }

    /** inquiry 최대 시도 횟수 = backoff 단계 수 (docs §5.3 "3회 실패 시 MANUAL_REVIEW"). */
    public int maxInquiryAttempts() {
        return inquiryBackoff.size();
    }

    /** {@code attempts} 번째 시도까지 기다릴 간격. 범위를 넘으면 마지막 간격을 쓴다. */
    public Duration backoffFor(int attempts) {
        int index = Math.min(attempts, inquiryBackoff.size() - 1);
        return inquiryBackoff.get(index);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Duration nullTo(Duration v, Duration fallback) {
        return v == null ? fallback : v;
    }
}
