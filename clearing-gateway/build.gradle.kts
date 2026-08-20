plugins { alias(libs.plugins.springBoot) }

description = "청산망 게이트웨이: pacs.008 송신 / pacs.002 수신 / timeout 감지 / pacs.028 inquiry"

dependencies {
    // payment-core 를 임베드해 청산 구간의 상태 전이를 같은 트랜잭션에서 수행한다 (ADR-0008).
    implementation(project(":payment-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.artemis)
    implementation(libs.micrometer.registry.prometheus)
    // ibmmq 프로파일에서만 사용되는 ConnectionFactory (ADR-0002)
    implementation(libs.ibm.mq.jakarta.client)
    runtimeOnly(libs.oracle.jdbc)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // 시나리오 테스트는 실물 시뮬레이터를 같은 JVM 의 별도 컨텍스트로 띄운다 (support/SimulatorProcess).
    testImplementation(project(":clearing-simulator"))
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.activemq)
    testImplementation(libs.awaitility)
}
