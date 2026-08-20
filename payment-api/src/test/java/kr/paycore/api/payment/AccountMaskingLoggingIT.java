package kr.paycore.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.paycore.api.support.AbstractPaymentApiIT;
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
 * 계좌번호 로그 마스킹 검증 (docs §5.1, §10.2).
 *
 * <p>"마스킹 유틸에 테스트가 있다"와 "실제 접수 경로의 로그에 원본이 안 남는다"는 다른 문제다. 여기서는
 * 후자를 검증한다 — 실제 애플리케이션 로그를 캡처해 원본 계좌번호 문자열이 등장하지 않음을 확인한다.
 */
class AccountMaskingLoggingIT extends AbstractPaymentApiIT {

    private static final String DEBTOR = "110-123-456789";
    private static final String CREDITOR = "352-987-654321";

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger rootLogger;

    @BeforeEach
    void attachAppender() {
        cleanDatabase();
        rootLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("kr.paycore");
        rootLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("접수 로그에 원본 계좌번호는 없고 마스킹된 형태만 남는다")
    void intakeLogsMaskedAccountsOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-mask-001");
        String body = """
                {
                  "debtorAccount": "%s",
                  "creditorAccount": "%s",
                  "creditorBankCode": "088",
                  "amount": 1500000,
                  "currency": "KRW",
                  "remittanceInfo": "8월 대금"
                }
                """.formatted(DEBTOR, CREDITOR);

        rest.exchange("/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        String logs =
                appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);

        assertThat(logs).as("접수 로그가 남아야 한다").contains("결제 접수");
        assertThat(logs).as("원본 계좌번호가 로그에 남으면 안 된다").doesNotContain(DEBTOR).doesNotContain(CREDITOR);
        assertThat(logs).contains("110-***-***789").contains("352-***-***321");
    }
}
