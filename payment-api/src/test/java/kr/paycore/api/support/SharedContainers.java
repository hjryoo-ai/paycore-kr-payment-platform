package kr.paycore.api.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트가 공유하는 컨테이너.
 *
 * <p>스프링 빈이 아니라 <b>정적 싱글턴</b>으로 둔 이유: 테스트 프로퍼티가 다르면 스프링 컨텍스트가 새로
 * 만들어지는데, 컨테이너까지 빈으로 두면 그때마다 Oracle 이 다시 뜬다. 컨테이너 수명을 JVM 에 맞추면
 * 컨텍스트가 몇 개가 되든 Oracle/Kafka 는 한 번만 뜬다.
 *
 * <p>이미지 태그는 docker-compose.yml 과 같은 것을 쓴다 — "테스트는 통과하는데 실제 기동은 다르다"를
 * 막는 가장 값싼 방법이다.
 */
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
