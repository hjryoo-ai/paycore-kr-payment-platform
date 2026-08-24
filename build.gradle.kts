import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.dependencyCheck)
}

// 스크립트 최상위에서 catalog 값을 읽어 subprojects 블록으로 전달한다.
val javaVersion = libs.versions.java.get()
val springBootBom = "org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"

allprojects {
    group = "kr.paycore"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(javaVersion) }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
        systemProperty("testcontainers.reuse.enable", System.getenv("TC_REUSE") ?: "false")
        // 통합 테스트는 Docker 데몬을 필요로 한다. CI 에서는 docker agent 위에서 돈다(docs §10.1).
    }

    dependencies {
        add("implementation", platform(springBootBom))
        add("annotationProcessor", platform(springBootBom))
        add("testImplementation", platform(springBootBom))
        add("testAnnotationProcessor", platform(springBootBom))
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        // Gradle 9 은 JUnit Platform launcher 를 테스트 런타임에서 명시적으로 요구한다.
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            palantirJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}

// OWASP Dependency-Check (docs §10.1 stage 4). CI에서 `gradle dependencyCheckAnalyze` 로만 실행.
// Spring Boot 모듈의 실행 가능 jar 이름을 고정한다.
//
// 왜: Dockerfile 의 `COPY build/libs/*.jar` 는 build/libs 에 -plain.jar 와 boot jar 두 개가 있을 때
// 두 파일 모두 같은 목적지에 쓰이고 '마지막에 복사된 것'이 남는다. 지금 동작하는 이유는 순전히
// '-'(0x2D) 가 '.'(0x2E) 보다 먼저 정렬되어 boot jar 가 나중에 덮어쓰기 때문이고,
// BuildKit 을 끄면 그대로 실패한다. 이름을 고정해 우연에 기대지 않는다.
subprojects {
    plugins.withId("org.springframework.boot") {
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            archiveFileName = "app.jar"
        }
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    // NVD_API_KEY 는 선택이 아니라 필수다. 13.0.0 은 키가 없으면 빈 문자열을 그대로
    // NVD 에 보내고 NVD 가 "Invalid apiKey" 로 거절한다:
    //   NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
    // 원인이 전혀 드러나지 않는 메시지라 한 번 적어 둔다 — 상류 버그다.
    //   dependencycheck.properties 에 `nvd.api.key=` 가 빈 값으로 들어 있고,
    //   Settings.getString 은 그 ""를 그대로 돌려주는데,
    //   NvdApiDataSource 는 `if (key != null)` 만 보고 빈 값을 거르지 않는다.
    // 그래서 여기서 빈 값을 걸러도 소용이 없다(플러그인도 setStringIfNotEmpty 를 쓴다).
    // 진짜 키를 넣는 것 외에 방법이 없다. 조건을 남겨 두는 건 의도를 드러내기 위해서다.
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey = it }
    suppressionFile = "config/dependency-check-suppressions.xml"
    // OSS Index 는 자격증명이 없으면 어차피 스스로 비활성화되면서 jar 마다 에러 한 줄씩을 남긴다.
    // 켜져 있다는 착시만 주므로 명시적으로 끈다. 취약점 판정은 NVD 가 한다.
    analyzers.ossIndex.enabled = false
}
