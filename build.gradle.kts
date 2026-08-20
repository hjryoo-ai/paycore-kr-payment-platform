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
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
    suppressionFile = "config/dependency-check-suppressions.xml"
}
