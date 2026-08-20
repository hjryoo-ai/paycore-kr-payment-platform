package kr.paycore.recon.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconProperties.class)
public class ReconConfiguration {

    /** 업무일자 계산의 기준이 되는 시계 (ADR-0010). */
    @Bean
    public Clock paycoreClock(ReconProperties properties) {
        return Clock.system(ZoneId.of(properties.zone()));
    }

    /**
     * 시뮬레이터에서 EOD 를 받아올 클라이언트.
     *
     * <p>타임아웃을 반드시 건다 — 마감 배치가 응답 없는 상대를 무한정 기다리면 마감 자체가 멈추고,
     * 멈춘 마감은 "불일치 0건"과 구분되지 않는다.
     */
    @Bean
    public RestClient eodRestClient(ReconProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.fetchTimeout());
        factory.setReadTimeout(properties.fetchTimeout());
        return RestClient.builder()
                .baseUrl(properties.simulatorBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
