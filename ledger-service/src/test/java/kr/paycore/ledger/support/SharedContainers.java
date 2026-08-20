package kr.paycore.ledger.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/** 원장 통합 테스트가 공유하는 인프라. 이미지 태그는 docker-compose.yml 과 같다. */
public final class SharedContainers {

    public static final OracleContainer ORACLE = new OracleContainer(
                    DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername("paycore")
            .withPassword("paycore");

    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    static {
        ORACLE.start();
        KAFKA.start();
    }

    private SharedContainers() {}
}
