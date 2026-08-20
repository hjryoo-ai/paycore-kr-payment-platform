package kr.paycore.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import kr.paycore.api.support.AbstractPaymentApiIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 장애 시나리오 #1 — 클라이언트 이중 클릭 (docs §8).
 *
 * <p>합격 기준: 같은 Idempotency-Key 로 여러 번 POST 해도 PAYMENT 는 1건이고, 두 번째부터는 동일 응답.
 */
class PaymentIntakeIdempotencyIT extends AbstractPaymentApiIT {

    private static final String BODY = """
            {
              "debtorAccount": "110-123-456789",
              "creditorAccount": "352-987-654321",
              "creditorBankCode": "088",
              "amount": 1500000,
              "currency": "KRW",
              "remittanceInfo": "8월 대금"
            }
            """;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 두 번 POST 하면 PAYMENT 는 1건이고 응답 본문이 완전히 같다")
    void sequentialRetryReturnsStoredResponse() {
        String key = "idem-seq-001";

        ResponseEntity<String> first = post(key, BODY);
        ResponseEntity<String> second = post(key, BODY);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(first.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("false");
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 10요청이 같은 Idempotency-Key 를 쓰면 PAYMENT 는 1건, 응답 10개가 모두 동일하다")
    void concurrentRequestsCollapseToSinglePayment() throws Exception {
        String key = "idem-race-001";
        int threads = 10;

        CyclicBarrier gate = new CyclicBarrier(threads);
        List<Callable<ResponseEntity<String>>> tasks = IntStream.range(0, threads)
                .<Callable<ResponseEntity<String>>>mapToObj(i -> () -> {
                    gate.await(); // 최대한 같은 순간에 출발시켜 UNIQUE 제약 경합을 실제로 만든다
                    return post(key, BODY);
                })
                .toList();

        List<ResponseEntity<String>> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<ResponseEntity<String>>> futures = pool.invokeAll(tasks);
            responses = futures.stream().map(PaymentIntakeIdempotencyIT::get).toList();
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED));
        assertThat(responses.stream().map(ResponseEntity::getBody).distinct())
                .as("응답 본문은 바이트 단위로 모두 같아야 한다 — 재실행이 아니라 저장된 최초 응답을 돌려주므로")
                .hasSize(1);
        assertThat(countPayments()).as("PAYMENT 는 정확히 1건").isEqualTo(1);

        long replayed = responses.stream()
                .filter(r -> "true".equals(r.getHeaders().getFirst("Idempotent-Replay")))
                .count();
        assertThat(replayed).as("정확히 한 요청만 신규 생성이고 나머지는 재생").isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("서로 다른 Idempotency-Key 는 각각 별개 결제로 접수된다")
    void differentKeysCreateSeparatePayments() {
        post("idem-a", BODY);
        post("idem-b", BODY);

        assertThat(countPayments()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 에 다른 본문을 보내면 422 로 거절한다 (조용한 오이체 방지)")
    void sameKeyDifferentBodyIsRejected() {
        String key = "idem-conflict-001";
        post(key, BODY);

        ResponseEntity<String> conflict = post(key, BODY.replace("1500000", "9900000"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(conflict.getBody()).contains("PC-V003");
        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400 + problem+json 으로 거절한다")
    void missingIdempotencyKeyIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                rest.exchange("/api/v1/payments", HttpMethod.POST, new HttpEntity<>(BODY, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PC-V002");
        assertThat(countPayments()).isZero();
    }

    @Test
    @DisplayName("접수 응답에는 paymentId / endToEndId / RECEIVED 상태가 담긴다")
    void acceptedResponseShape() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/payments",
                HttpMethod.POST,
                new HttpEntity<>(BODY, jsonHeaders("idem-shape-001")),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsKeys("paymentId", "endToEndId", "status", "acceptedAt");
        assertThat(response.getBody().get("status")).isEqualTo("RECEIVED");
        assertThat((String) response.getBody().get("paymentId")).hasSize(26);
        assertThat((String) response.getBody().get("endToEndId"))
                .startsWith("PC")
                .hasSize(28);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/v1/payments/" + response.getBody().get("paymentId"));
    }

    private ResponseEntity<String> post(String idempotencyKey, String body) {
        return rest.exchange(
                "/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(idempotencyKey)), String.class);
    }

    private static HttpHeaders jsonHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
