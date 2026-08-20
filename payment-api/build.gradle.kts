plugins { alias(libs.plugins.springBoot) }

description = "채널 API: 결제 접수 / 멱등성 / 조회. payment-core 를 같은 프로세스에서 구동 (docs §4.1, ADR-0003)"

dependencies {
    implementation(project(":payment-core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
