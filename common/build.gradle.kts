plugins { `java-library` }

description = "공유 커널: ID 생성기, 에러코드, ISO 20022 축약 메시지 스키마/DTO, 마스킹 유틸"

dependencies {
    // 원칙: common 에는 '서비스 간 공유 DTO'를 넣지 않는다(docs §3.1).
    // 넣는 것은 (1) 메시지 계약(pacs.*) (2) ID 생성 (3) 에러코드 (4) 로깅 마스킹 뿐이다.
    api(libs.ulid.creator)
    api(libs.json.schema.validator)
    api("com.fasterxml.jackson.core:jackson-annotations")
    api("tools.jackson.core:jackson-databind")
    compileOnly("org.springframework:spring-context")
}
