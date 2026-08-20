package kr.paycore.api.support;

import java.time.Duration;
import kr.paycore.api.PaymentApiApplication;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Oracle + Kafka 컨테이너와 실제 웹 서버를 띄우는 통합 테스트의 공통 베이스. */
@SpringBootTest(classes = PaymentApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class AbstractPaymentApiIT {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.ORACLE::getUsername);
        registry.add("spring.datasource.password", SharedContainers.ORACLE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::getBootstrapServers);
    }

    /** 통합 테스트에서 Thread.sleep 금지(CLAUDE.md). 기다릴 일은 전부 이 헬퍼로. */
    protected static ConditionFactory await() {
        return Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200));
    }

    /**
     * 테스트 간 격리를 위한 초기화.
     *
     * <p>먼저 비동기 검증이 끝나기를 기다린다. 진행 중에 지우면 상태 이력 INSERT 와 PAYMENT DELETE 가
     * 경합해 {@code ORA-02292 (FK_HIST_PAYMENT)} 가 난다 — 실제로 겪은 실패다.
     * 삭제 순서도 자식 → 부모여야 한다.
     */
    protected void cleanDatabase() {
        await().until(() -> countByStatus("RECEIVED") == 0);
        jdbc.execute("DELETE FROM OUTBOX_EVENT");
        jdbc.execute("DELETE FROM PAYMENT_STATUS_HISTORY");
        jdbc.execute("DELETE FROM PAYMENT");
        jdbc.execute("DELETE FROM DAILY_LIMIT");
    }

    protected long countByStatus(String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM PAYMENT WHERE STATUS = ?", Long.class, status);
        return n == null ? 0L : n;
    }

    protected long countPayments() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM PAYMENT", Long.class);
        return n == null ? 0L : n;
    }

    protected long countOutbox(String status) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM OUTBOX_EVENT WHERE STATUS = ?", Long.class, status);
        return n == null ? 0L : n;
    }

    protected String paymentStatus(String paymentId) {
        return jdbc.queryForObject("SELECT STATUS FROM PAYMENT WHERE PAYMENT_ID = ?", String.class, paymentId);
    }
}
