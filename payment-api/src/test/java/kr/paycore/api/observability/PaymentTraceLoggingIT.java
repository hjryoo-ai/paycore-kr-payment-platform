package kr.paycore.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import kr.paycore.api.support.AbstractPaymentApiIT;
import kr.paycore.core.observability.PaymentMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * 결제 1건의 전 구간 로그를 endToEndId 하나로 찾을 수 있는가 (docs §10.3).
 *
 * <p>"MDC 유틸에 단위 테스트가 있다"와 "실제 처리 경로의 로그에 endToEndId 가 붙는다"는 다른 문제다.
 * 여기서는 후자를 검증한다 — 실제 접수·검증 경로가 남긴 로그 이벤트의 MDC 맵을 직접 본다.
 *
 * <p>그리고 <b>MDC 가 새지 않는지</b>도 함께 본다. 풀 스레드에 남은 MDC 는 다음 결제의 로그에
 * 엉뚱한 endToEndId 를 붙이는데, 그건 로그가 없는 것보다 나쁘다.
 */
class PaymentTraceLoggingIT extends AbstractPaymentApiIT {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger paycoreLogger;

    @BeforeEach
    void attachAppender() {
        cleanDatabase();
        paycoreLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("kr.paycore");
        paycoreLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        paycoreLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        paycoreLogger.detachAppender(appender);
        appender.stop();
    }

    private String submit(long amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = """
                {
                  "debtorAccount": "110-123-456789",
                  "creditorAccount": "352-987-654321",
                  "creditorBankCode": "088",
                  "amount": %d,
                  "currency": "KRW",
                  "remittanceInfo": "MDC 추적"
                }
                """.formatted(amount);
        String response = rest.exchange(
                        "/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getBody();
        return response;
    }

    @Test
    @DisplayName("접수부터 검증까지의 로그가 같은 endToEndId 로 묶인다")
    void logsAreCorrelatedByEndToEndId() {
        String response = submit(1_500_000L);
        String paymentId = field(response, "paymentId");
        String endToEndId = field(response, "endToEndId");

        // 검증은 커밋 후 비동기라 로그가 나중에 붙는다.
        await().until(() -> countByStatus("VALIDATED") == 1);

        List<ILoggingEvent> tagged = appender.list.stream()
                .filter(e -> endToEndId.equals(e.getMDCPropertyMap().get(PaymentMdc.END_TO_END_ID)))
                .toList();

        assertThat(tagged).as("접수(payment-api 스레드)와 검증(비동기 풀 스레드) 양쪽이 잡혀야 한다").hasSizeGreaterThanOrEqualTo(2);
        assertThat(tagged)
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry(PaymentMdc.PAYMENT_ID, paymentId));
        assertThat(tagged.stream().map(ILoggingEvent::getThreadName).distinct())
                .as("서로 다른 스레드의 로그가 같은 키로 묶인다 — 그게 이 MDC 의 존재 이유다")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(tagged).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("RECEIVED -> VALIDATED"));
    }

    @Test
    @DisplayName("결제가 다르면 로그도 섞이지 않는다 — 풀 스레드에 MDC 가 새면 여기서 깨진다")
    void mdcDoesNotLeakBetweenPayments() {
        String first = submit(1_100_000L);
        String second = submit(1_200_000L);
        String firstE2e = field(first, "endToEndId");
        String secondE2e = field(second, "endToEndId");
        String firstId = field(first, "paymentId");
        String secondId = field(second, "paymentId");

        await().until(() -> countByStatus("VALIDATED") == 2);

        // 한 결제의 endToEndId 가 붙은 줄에 다른 결제의 paymentId 가 섞이면 MDC 가 샌 것이다.
        assertThat(appender.list.stream()
                        .filter(e -> firstE2e.equals(e.getMDCPropertyMap().get(PaymentMdc.END_TO_END_ID)))
                        .toList())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap().get(PaymentMdc.PAYMENT_ID))
                        .isEqualTo(firstId));
        assertThat(appender.list.stream()
                        .filter(e -> secondE2e.equals(e.getMDCPropertyMap().get(PaymentMdc.END_TO_END_ID)))
                        .toList())
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap().get(PaymentMdc.PAYMENT_ID))
                        .isEqualTo(secondId));
    }

    @Test
    @DisplayName("MDC 에도 원본 계좌번호는 넣지 않는다 — 추적 키가 개인정보 유출 통로가 되면 안 된다")
    void mdcCarriesNoAccountNumbers() {
        submit(1_300_000L);
        await().until(() -> countByStatus("VALIDATED") == 1);

        assertThat(appender.list)
                .allSatisfy(e -> assertThat(e.getMDCPropertyMap().values())
                        .noneMatch(v -> v != null && (v.contains("110-123-456789") || v.contains("352-987-654321"))));
    }

    private static String field(String json, String name) {
        int i = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }
}
