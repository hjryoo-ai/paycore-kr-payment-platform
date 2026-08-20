package kr.paycore.common.clearing;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 청산 메시지 직렬화 + <b>스키마 검증</b> (docs §5.3, CLAUDE.md — pacs.* 는 JSON Schema 가 원본).
 *
 * <p>보낼 때도 검증한다. 받을 때만 검증하면 "우리가 규격 위반 메시지를 만들어 보낸 사실"을 상대방
 * 장애로 착각하게 되고, 결제망에서 그 착각은 재송신 → 이중 지급으로 이어진다.
 *
 * <p>스키마 파일은 {@code common/src/main/resources/schemas/} 에 있고, {@code $id} 의
 * {@code https://paycore.kr/schemas/} 접두어를 클래스패스로 매핑해 네트워크 조회 없이 해결한다.
 * 이 클래스는 스레드 안전하며 애플리케이션당 하나만 두면 된다.
 */
public class ClearingMessageCodec {

    private static final String SCHEMA_ID_PREFIX = "https://paycore.kr/schemas/";
    private static final String CLASSPATH_PREFIX = "classpath:schemas/";

    private final ObjectMapper objectMapper;
    private final Map<Class<?>, Schema> schemas;

    public ClearingMessageCodec() {
        this(defaultObjectMapper());
    }

    public ClearingMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaIdResolvers(r -> r.mapPrefix(SCHEMA_ID_PREFIX, CLASSPATH_PREFIX)));
        this.schemas = Map.of(
                Pacs008.class, load(registry, "pacs.008.json"),
                Pacs002.class, load(registry, "pacs.002.json"),
                Pacs028.class, load(registry, "pacs.028.json"));
    }

    /**
     * 시각을 ISO-8601 문자열로 쓰는 매퍼. 기본값(에포크 소수)으로 두면 스키마의
     * {@code format: date-time} 과 어긋나고, 로그·대사에서 사람이 읽을 수 없게 된다.
     */
    private static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    private static Schema load(SchemaRegistry registry, String fileName) {
        return registry.getSchema(com.networknt.schema.SchemaLocation.of(SCHEMA_ID_PREFIX + fileName));
    }

    /** 객체 → JSON. 스키마를 통과하지 못하면 보내지 않는다. */
    public String encode(Object message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new ClearingMessageException(
                    "청산 메시지 직렬화 실패: " + message.getClass().getSimpleName(), e);
        }
        validate(message.getClass(), json, "송신");
        return json;
    }

    /** JSON → 객체. 스키마를 통과하지 못하면 비즈니스 로직을 태우지 않는다. */
    public <T> T decode(String json, Class<T> type) {
        validate(type, json, "수신");
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new ClearingMessageException("청산 메시지 역직렬화 실패: " + type.getSimpleName(), e);
        }
    }

    private void validate(Class<?> type, String json, String phase) {
        Schema schema = schemas.get(type);
        if (schema == null) {
            throw new ClearingMessageException("스키마가 등록되지 않은 메시지 타입: " + type.getName(), List.of());
        }
        List<String> violations;
        try {
            violations = schema.validate(json, InputFormat.JSON).stream()
                    .map(e -> e.getInstanceLocation() + " " + e.getMessage())
                    .toList();
        } catch (RuntimeException e) {
            throw new ClearingMessageException(phase + " 메시지가 JSON 이 아니다: " + type.getSimpleName(), e);
        }
        if (!violations.isEmpty()) {
            throw new ClearingMessageException(phase + " 메시지 스키마 위반: " + type.getSimpleName(), violations);
        }
    }
}
