package kr.paycore.core.messaging;

import kr.paycore.core.config.PaymentCoreProperties;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 재시도 · DLT 정책 (docs §7.5). 모든 소비자가 같은 규칙을 쓴다.
 *
 * <p><b>일시 오류와 영구 오류를 구분하는 것이 이 설정의 전부다.</b> 깨진 payload 를 DB 커넥션
 * 오류와 똑같이 재시도하면 그 메시지 하나가 파티션을 영원히 막고, 뒤에 있는 정상 결제들이
 * 함께 멈춘다. 반대로 일시 오류를 곧바로 DLT 로 보내면 브로커가 잠깐 흔들린 것만으로
 * 멀쩡한 결제가 사람 손을 타게 된다.
 *
 * <ul>
 *   <li>{@link PermanentMessageException}·역직렬화 실패 → <b>재시도 없이 즉시 DLT</b>
 *   <li>그 밖의 예외(DB 커넥션 등) → 지수 backoff 로 N회 재시도 후 DLT
 * </ul>
 *
 * <p>DLT 파티션은 원본과 같은 번호를 쓴다. 그래야 같은 결제(파티션 키 = paymentId)의 실패가
 * 한 파티션에 모여 순서대로 보인다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public CommonErrorHandler paycoreKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaRetryProperties retry,
            PaymentCoreProperties coreProperties) {

        String dltTopic = coreProperties.eventsTopic() + retry.dltSuffix();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(dltTopic, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(retry.initialBackoff().toMillis());
        backOff.setMultiplier(retry.backoffMultiplier());
        backOff.setMaxInterval(retry.maxBackoff().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 재시도해도 같은 결과인 것들. 여기 빠지면 poison message 가 파티션을 막는다.
        handler.addNotRetryableExceptions(PermanentMessageException.class, DeserializationException.class);
        handler.setAckAfterHandle(true);
        handler.setRetryListeners((record, exception, deliveryAttempt) -> log.warn(
                "소비 실패 재시도 {}회차 topic={} partition={} offset={} 원인={}",
                deliveryAttempt,
                record.topic(),
                record.partition(),
                record.offset(),
                exception.toString()));
        return handler;
    }
}
