package kr.paycore.gateway.config;

import kr.paycore.common.clearing.ClearingMessageCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    /** {@code Clock}/{@code Ids} 는 payment-core 자동설정이 제공한다(ADR-0008). 여기서는 코덱만 더한다. */
    @Bean
    public ClearingMessageCodec clearingMessageCodec() {
        return new ClearingMessageCodec();
    }
}
