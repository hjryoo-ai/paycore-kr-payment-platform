plugins { `java-library` }

description = "결제 오케스트레이션: 상태머신 소유, 비즈니스 검증, Transactional Outbox"

dependencies {
    api(project(":common"))
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-validation")
    api(libs.spring.boot.kafka)
    api(libs.spring.kafka)
    // 메트릭은 core 가 낸다 — 어느 프로세스에 패키징되든 같은 지표가 나와야 한다 (docs §10.3).
    api(libs.micrometer.core)
    api(libs.spring.boot.flyway)
    api(libs.flyway.core)
    runtimeOnly(libs.flyway.database.oracle)
    runtimeOnly(libs.oracle.jdbc)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.awaitility)
}
