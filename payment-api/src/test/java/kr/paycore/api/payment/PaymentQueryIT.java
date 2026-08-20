package kr.paycore.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import kr.paycore.api.support.AbstractPaymentApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 조회 API (docs §5.1). 응답에서도 계좌번호는 마스킹된다. */
class PaymentQueryIT extends AbstractPaymentApiIT {

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    @DisplayName("단건 조회는 상태 타임라인을 포함하고 계좌번호는 마스킹되어 나간다")
    void getByIdReturnsMaskedDetailWithTimeline() {
        String paymentId = accept("idem-query-001");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/payments/" + paymentId, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("paymentId")).isEqualTo(paymentId);
        assertThat(body.get("status"))
                .as("접수 직후 조회라 RECEIVED 이거나 이미 VALIDATED 일 수 있다")
                .isIn("RECEIVED", "VALIDATED");
        assertThat(body.get("debtorAccount")).isEqualTo("110-***-***789");
        assertThat(body.get("creditorAccount")).isEqualTo("352-***-***321");

        @SuppressWarnings("unchecked")
        var history = (java.util.List<Map<String, Object>>) body.get("history");
        assertThat(history).isNotEmpty();
        assertThat(history.getFirst())
                .containsEntry("from", null)
                .containsEntry("to", "RECEIVED")
                .containsEntry("triggeredBy", "channel-api");
    }

    @Test
    @DisplayName("없는 결제 건 조회는 404 problem+json")
    void getMissingPaymentReturns404() {
        ResponseEntity<String> response =
                rest.exchange("/api/v1/payments/01ABCDEFGHJKMNPQRSTVWXYZ00", HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("PC-B001");
    }

    @Test
    @DisplayName("목록 조회는 페이징되고 상태로 필터링된다")
    void searchByStatus() {
        // Phase 2 부터 접수 건은 비동기 검증을 거쳐 곧 VALIDATED 가 된다. 상태가 안정될 때까지 기다린 뒤 조회한다.
        accept("idem-list-001");
        accept("idem-list-002");
        accept("idem-list-003");
        await().until(() -> countByStatus("VALIDATED") == 3);

        ResponseEntity<Map<String, Object>> validated = rest.exchange(
                "/api/v1/payments?status=VALIDATED&size=2",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        ResponseEntity<Map<String, Object>> settled = rest.exchange(
                "/api/v1/payments?status=SETTLED", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(validated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validated.getBody()).containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat((java.util.List<?>) validated.getBody().get("content")).hasSize(2);
        assertThat(settled.getBody()).as("아직 원장 반영 전이므로 SETTLED 는 없다").containsEntry("totalElements", 0);
    }

    private String accept(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        String body = """
                {
                  "debtorAccount": "110-123-456789",
                  "creditorAccount": "352-987-654321",
                  "creditorBankCode": "088",
                  "amount": 1500000,
                  "currency": "KRW",
                  "remittanceInfo": "8월 대금"
                }
                """;
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
        return (String) response.getBody().get("paymentId");
    }
}
