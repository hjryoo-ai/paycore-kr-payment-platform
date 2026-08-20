plugins { alias(libs.plugins.springBoot) }

description = "일마감 3-way 대사: PAYMENT vs 청산망 EOD vs LEDGER (docs §5.6)"

dependencies {
    // PAYMENT/JOURNAL/LEDGER_ENTRY/RECON_BREAK 는 payment-core 가 소유한 스키마 도메인이다.
    implementation(project(":payment-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.restclient)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.oracle.jdbc)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
