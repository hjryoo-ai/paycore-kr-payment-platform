plugins { alias(libs.plugins.springBoot) }

description = "EOD 3-way 대사 배치: PAYMENT vs 청산망 EOD 파일 vs LEDGER"

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.oracle.jdbc)

    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testImplementation(libs.awaitility)
}
