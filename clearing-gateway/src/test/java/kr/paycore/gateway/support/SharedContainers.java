package kr.paycore.gateway.support;

import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 게이트웨이 통합 테스트가 공유하는 인프라. 이미지 태그는 docker-compose.yml 과 동일하게 맞춘다.
 *
 * <p>정적 싱글턴인 이유는 payment-api 쪽과 같다 — 컨텍스트가 여러 개여도 컨테이너는 한 벌만 뜬다.
 */
public final class SharedContainers {

    public static final OracleContainer ORACLE = new OracleContainer(
                    DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername("paycore")
            .withPassword("paycore");

    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    public static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.44.0")
            .withUser("paycore")
            .withPassword("paycore");

    static {
        ORACLE.start();
        KAFKA.start();
        ARTEMIS.start();
    }

    private SharedContainers() {}
}
