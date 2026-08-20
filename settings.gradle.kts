plugins {
    // Java 21 toolchain을 로컬에 없을 경우 자동 프로비저닝
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "paycore-kr"

include(
    "common",
    "payment-core",
    "payment-api",
    "clearing-gateway",
    "clearing-simulator",
    "ledger-service",
    "recon-batch",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
