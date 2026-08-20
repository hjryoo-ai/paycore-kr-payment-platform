package kr.paycore.ledger.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.paycore.common.id.Ids;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.ledger.JournalRepository;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.core.ledger.LedgerEntryRepository;
import kr.paycore.core.outbox.OutboxEvent;
import kr.paycore.core.outbox.OutboxEventRepository;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.PaymentStateMachine;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 원장 통합 테스트 기반 — Oracle 과 Kafka 는 실물이다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractLedgerIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void infraProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.ORACLE::getUsername);
        registry.add("spring.datasource.password", SharedContainers.ORACLE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::getBootstrapServers);
    }

    @Autowired
    protected PaymentRepository payments;

    @Autowired
    protected PaymentStatusHistoryRepository histories;

    @Autowired
    protected JournalRepository journals;

    @Autowired
    protected LedgerEntryRepository entries;

    @Autowired
    protected OutboxEventRepository outboxEvents;

    @Autowired
    protected OutboxWriter outbox;

    @Autowired
    protected PaymentStateMachine stateMachine;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected kr.paycore.core.inbox.ProcessedMessageRepository processedMessages;

    @org.springframework.beans.factory.annotation.Value("${paycore.core.events-topic}")
    protected String eventsTopic;

    @org.springframework.beans.factory.annotation.Value("${paycore.ledger.consumer-group}")
    protected String consumerGroup;

    @Autowired
    protected Ids ids;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM LEDGER_ENTRY");
        jdbc.update("DELETE FROM JOURNAL");
        jdbc.update("DELETE FROM CLEARING_MESSAGE_LOG");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        jdbc.update("DELETE FROM PROCESSED_MESSAGE");
        jdbc.update("DELETE FROM PAYMENT_STATUS_HISTORY");
        jdbc.update("DELETE FROM PAYMENT");
        jdbc.update("DELETE FROM DAILY_LIMIT");
    }

    protected ConditionFactory awaitCondition() {
        return await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200));
    }

    /**
     * 청산까지 끝난 결제를 만든다. 상태는 빌더로 찍지 않고 전이표를 따라 올린다 — 준비 과정에서도
     * 상태머신을 우회하지 않아야 "테스트에서만 존재하는 상태"가 생기지 않는다.
     */
    protected Payment givenClearedPayment(long amount) {
        return tx.execute(status -> {
            int n = SEQ.incrementAndGet();
            Payment draft = Payment.builder()
                    .paymentId(ids.newPaymentId())
                    .idempotencyKey("ledger-it-" + ids.newEventId())
                    .endToEndId(ids.newEndToEndId())
                    .debtorAccount("110-123-4567" + String.format("%02d", n % 100))
                    .creditorAccount("352-987-6543" + String.format("%02d", n % 100))
                    .creditorBank("088")
                    .amount(amount)
                    .currency("KRW")
                    .remittanceInfo("원장 통합테스트")
                    .status(PaymentStatus.RECEIVED)
                    .createdAt(ids.now())
                    .build();
            // save() 의 반환값을 써야 한다 — PAYMENT 는 merge 로 저장되어 원본이 준영속으로 남는다.
            Payment payment = payments.saveAndFlush(draft);
            histories.save(new PaymentStatusHistory(
                    payment.paymentId(), null, PaymentStatus.RECEIVED, "test-fixture", "접수", ids.now()));
            stateMachine.transition(payment, PaymentStatus.VALIDATED, "test-fixture", null);
            stateMachine.transition(payment, PaymentStatus.SENT_TO_CLEARING, "test-fixture", null);
            stateMachine.transition(payment, PaymentStatus.CLEARED, "test-fixture", null);
            return payment;
        });
    }

    protected PaymentClearedEvent clearedEventFor(Payment payment) {
        return new PaymentClearedEvent(
                payment.paymentId(),
                payment.endToEndId(),
                ids.newClearingMsgId(),
                payment.amount(),
                payment.currency(),
                payment.debtorAccount(),
                payment.creditorAccount(),
                false,
                ids.now());
    }

    /** 아웃박스에 PaymentCleared 를 남긴다. poller 가 실제로 Kafka 로 내보낸다. */
    protected String publishCleared(Payment payment) {
        return tx.execute(
                status -> outbox.append(payment.paymentId(), PaymentEventType.PAYMENT_CLEARED, clearedEventFor(payment))
                        .eventId());
    }

    protected List<LedgerEntry> entriesOf(String paymentId) {
        return journals.findByPaymentId(paymentId)
                .map(j -> entries.findByJournalIdOrderByDrCrAsc(j.journalId()))
                .orElse(List.of());
    }

    protected long processedMessageCount() {
        return processedMessages.count();
    }

    protected List<OutboxEvent> outboxOf(String paymentId, String eventType) {
        return outboxEvents.findAll().stream()
                .filter(e -> e.aggregateId().equals(paymentId) && e.eventType().equals(eventType))
                .toList();
    }
}
