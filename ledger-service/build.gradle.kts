plugins { alias(libs.plugins.springBoot) }

description = "복식부기 원장: PaymentCleared 소비 -> 분개 2건 -> PaymentSettled 발행"

dependencies {
    // inbox dedup 과 아웃박스는 payment-core 가 소유한다 (CLAUDE.md 불변식 3·4).
    implementation(project(":payment-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.oracle.jdbc)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
