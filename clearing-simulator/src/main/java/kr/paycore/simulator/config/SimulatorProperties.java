package kr.paycore.simulator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시뮬레이터 운영 파라미터 (docs §5.4).
 *
 * @param requestQueue 이체 지시(pacs.008) / 상태조회(pacs.028) 수신 큐
 * @param responseQueue 상태 응답(pacs.002) 송신 큐
 * @param eodDir EOD CSV 출력 디렉터리
 * @param defaultDelay DELAY 모드의 기본 지연
 * @param outOfOrderBatch OUT_OF_ORDER 모드에서 몇 건을 모아 순서를 뒤집을지
 * @param outOfOrderMaxHold 위 버퍼를 최대 얼마나 붙들지 — 무한 대기는 장애지 시뮬레이션이 아니다
 * @param zone 업무일자 계산 기준 시간대
 */
@ConfigurationProperties(prefix = "paycore.simulator")
public record SimulatorProperties(
        String requestQueue,
        String responseQueue,
        String eodDir,
        Duration defaultDelay,
        int outOfOrderBatch,
        Duration outOfOrderMaxHold,
        String zone) {

    public SimulatorProperties {
        requestQueue = blankTo(requestQueue, "CLR.REQ");
        responseQueue = blankTo(responseQueue, "CLR.RES");
        eodDir = blankTo(eodDir, "./data/eod");
        defaultDelay = defaultDelay == null ? Duration.ofSeconds(15) : defaultDelay;
        outOfOrderBatch = outOfOrderBatch < 2 ? 2 : outOfOrderBatch;
        outOfOrderMaxHold = outOfOrderMaxHold == null ? Duration.ofSeconds(2) : outOfOrderMaxHold;
        zone = blankTo(zone, "Asia/Seoul");
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
