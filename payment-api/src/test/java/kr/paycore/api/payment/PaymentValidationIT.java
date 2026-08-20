package kr.paycore.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import kr.paycore.api.support.AbstractPaymentApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 입력 검증 (docs §5.1 secure coding). 거절된 요청은 DB에 흔적을 남기지 않아야 한다. */
class PaymentValidationIT extends AbstractPaymentApiIT {

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "\"debtorAccount\": \"110-123-456789\"|계좌번호에 문자가 섞이면 거절|\"debtorAccount\": \"110-abc-456789\"",
                "\"creditorBankCode\": \"088\"|화이트리스트에 없는 은행코드는 거절|\"creditorBankCode\": \"999\"",
                "\"creditorBankCode\": \"088\"|은행코드 자릿수가 다르면 거절|\"creditorBankCode\": \"88\"",
                "\"amount\": 1500000|금액 0 은 거절|\"amount\": 0",
                "\"amount\": 1500000|금액 음수는 거절|\"amount\": -1000",
                "\"amount\": 1500000|1건 한도(10억) 초과는 거절|\"amount\": 1000000001",
                "\"currency\": \"KRW\"|원화 외 통화는 거절|\"currency\": \"USD\"",
                "\"remittanceInfo\": \"8월 대금\"|적요 개행문자는 거절(로그 인젝션 방지)|\"remittanceInfo\": \"8월\\n대금\"",
                "\"debtorAccount\": \"110-123-456789\"|자릿수가 모자란 계좌번호는 거절|\"debtorAccount\": \"12-3\"",
                "\"creditorAccount\": \"352-987-654321\"|입금계좌 자릿수도 검사한다|\"creditorAccount\": \"123-4\"",
            })
    @DisplayName("잘못된 요청은 400 problem+json 으로 거절되고 PAYMENT 가 생성되지 않는다")
    void invalidRequestsAreRejected(String original, String description, String replacement) {
        String body = validBody().replace(original, replacement);

        ResponseEntity<String> response = post("idem-" + description.hashCode(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getBody()).contains("PC-V001");
        assertThat(countPayments()).isZero();
    }

    @Test
    @DisplayName("한글 적요 140자는 정상 접수된다 — 컬럼이 바이트로 세면 47자에서 500 이 났었다")
    void acceptsFullLengthKoreanRemittanceInfo() {
        String korean = "가".repeat(140);
        String body = validBody().replace("\"remittanceInfo\": \"8월 대금\"", "\"remittanceInfo\": \"" + korean + "\"");

        ResponseEntity<String> response = post("idem-korean-140", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    @DisplayName("한글 적요 141자는 400 으로 거절된다 — 500 이 아니다")
    void rejectsOverlongKoreanRemittanceInfo() {
        String korean = "가".repeat(141);
        String body = validBody().replace("\"remittanceInfo\": \"8월 대금\"", "\"remittanceInfo\": \"" + korean + "\"");

        ResponseEntity<String> response = post("idem-korean-141", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PC-V001");
        assertThat(countPayments()).isZero();
    }

    private static String validBody() {
        return """
                {
                  "debtorAccount": "110-123-456789",
                  "creditorAccount": "352-987-654321",
                  "creditorBankCode": "088",
                  "amount": 1500000,
                  "currency": "KRW",
                  "remittanceInfo": "8월 대금"
                }
                """;
    }

    private ResponseEntity<String> post(String key, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
