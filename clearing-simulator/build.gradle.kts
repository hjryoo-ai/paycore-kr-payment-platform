plugins { alias(libs.plugins.springBoot) }

description = "금융결제원 공동망 + 상대은행 시뮬레이터. 장애 주입 모드 API 제공 (docs §5.4)"

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.artemis)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.ibm.mq.jakarta.client)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.activemq)
    testImplementation(libs.awaitility)
}

