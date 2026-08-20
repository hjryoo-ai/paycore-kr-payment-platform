package kr.paycore.core.kafka;

import kr.paycore.core.config.PaymentCoreProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 내부 이벤트 토픽 (docs §3.2).
 *
 * <p>자동 생성에 맡기지 않고 명시 선언하는 이유: 파티션 수는 나중에 늘릴 수는 있어도 줄일 수 없고,
 * 파티션 키(paymentId) 기반 순서 보장이 이 시스템의 전제이기 때문에 우연에 맡길 값이 아니다.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

    static final int PARTITIONS = 3;
    static final short REPLICAS = 1; // 로컬 단일 브로커. 운영이라면 3 이상 + min.insync.replicas=2

    @Bean
    public NewTopic paymentEventsTopic(PaymentCoreProperties properties) {
        return TopicBuilder.name(properties.eventsTopic())
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
