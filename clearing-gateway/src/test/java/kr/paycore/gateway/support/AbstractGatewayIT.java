package kr.paycore.gateway.support;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Direction;
import kr.paycore.common.id.Ids;
import kr.paycore.core.clearing.ClearingMessageLog;
import kr.paycore.core.clearing.ClearingMessageLogRepository;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentValidatedEvent;
import kr.paycore.core.inbox.ProcessedMessageRepository;
import kr.paycore.core.outbox.OutboxEvent;
import kr.paycore.core.outbox.OutboxEventRepository;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.gateway.response.ClearingResponseListener;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 청산 게이트웨이 통합 테스트 기반 — Oracle · Kafka · Artemis · 시뮬레이터가 모두 실물이다. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractGatewayIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    static {
        SimulatorProcess.start();
    }

    @DynamicPropertySource
    static void infraProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.ORACLE::getUsername);
        registry.add("spring.datasource.password", SharedContainers.ORACLE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::getBootstrapServers);
        registry.add("spring.artemis.broker-url", SharedContainers.ARTEMIS::getBrokerUrl);
        registry.add("spring.artemis.user", SharedContainers.ARTEMIS::getUser);
        registry.add("spring.artemis.password", SharedContainers.ARTEMIS::getPassword);
        registry.add("paycore.gateway.request-queue", () -> SimulatorProcess.REQUEST_QUEUE);
        registry.add("paycore.gateway.response-queue", () -> SimulatorProcess.RESPONSE_QUEUE);
    }

    @Autowired
    protected PaymentRepository payments;

    @Autowired
    protected PaymentStatusHistoryRepository histories;

    @Autowired
    protected ClearingMessageLogRepository clearingLogs;

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
    protected ProcessedMessageRepository processedMessages;

    @Autowired
    protected Ids ids;

    @Autowired
    private JmsListenerEndpointRegistry jmsRegistry;

    @Autowired
    private JmsTemplate jms;

    /**
     * 테스트 사이 격리. 순서가 중요하다 — 양쪽 리스너를 먼저 세우지 않고 큐를 비우면, 비우는 도중에
     * 소비가 일어나 앞 테스트의 메시지가 처리 기록으로 되살아난다.
     */
    @BeforeEach
    void resetWorld() {
        SimulatorProcess.stopConsuming();
        stopGatewayResponseListener();

        drain(SimulatorProcess.REQUEST_QUEUE);
        drain(SimulatorProcess.RESPONSE_QUEUE);

        cleanDatabase();
        SimulatorProcess.reset();
        startGatewayResponseListener();
    }

    private void drain(String queue) {
        jms.setReceiveTimeout(200);
        int guard = 0;
        while (jms.receive(queue) != null && guard++ < 500) {
            // drain
        }
    }

    private void stopGatewayResponseListener() {
        var container = jmsRegistry.getListenerContainer(ClearingResponseListener.LISTENER_ID);
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    private void startGatewayResponseListener() {
        var container = jmsRegistry.getListenerContainer(ClearingResponseListener.LISTENER_ID);
        if (container != null && !container.isRunning()) {
            container.start();
        }
    }

    protected long receivedPacs002Count(String paymentId) {
        return clearingLogs.countByPaymentIdAndMsgTypeAndDirection(paymentId, ClearingMsgType.PACS_002, Direction.IN);
    }

    /** 이 결제로 실제 송신된 pacs.008 의 msgId. 응답을 위조·상관짓는 테스트가 이 값을 쓴다. */
    protected String sentPacs008MsgId(String paymentId) {
        return clearingLogs
                .findTopByPaymentIdAndMsgTypeAndDirectionOrderBySentAtDesc(
                        paymentId, ClearingMsgType.PACS_008, Direction.OUT)
                .orElseThrow(() -> new IllegalStateException("pacs.008 송신 기록이 없다 paymentId=" + paymentId))
                .msgId();
    }

    protected long processedMessageCount() {
        return processedMessages.count();
    }

    /** 자식 → 부모 순으로 지운다. FK 를 무시하고 지우면 테스트가 스키마를 감추게 된다. */
    protected void cleanDatabase() {
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
     * 검증까지 끝난 결제를 만들고 {@code PaymentValidated} 를 아웃박스에 남긴다.
     *
     * <p>상태는 빌더로 찍지 않고 상태머신을 통해 전이시킨다 — 테스트 준비 과정에서도 전이표를 우회하지
     * 않아야 "테스트에서만 가능한 상태"가 생기지 않는다.
     */
    protected Payment givenValidatedPayment(long amount) {
        return tx.execute(status -> {
            int n = SEQ.incrementAndGet();
            Payment payment = Payment.builder()
                    .paymentId(ids.newPaymentId())
                    .idempotencyKey("it-" + ids.newEventId())
                    .endToEndId(ids.newEndToEndId())
                    .debtorAccount("110-123-4567" + String.format("%02d", n % 100))
                    .creditorAccount("352-987-6543" + String.format("%02d", n % 100))
                    .creditorBank("088")
                    .amount(amount)
                    .currency("KRW")
                    .remittanceInfo("게이트웨이 통합테스트")
                    .status(PaymentStatus.RECEIVED)
                    .createdAt(ids.now())
                    .build();
            // save() 의 반환값을 써야 한다. PAYMENT 는 ID 가 직접 할당되고 @Version 이 primitive 라
            // Spring Data 가 이 엔티티를 '새 것'으로 보지 않고 merge 로 저장하며, merge 는 원본이 아니라
            // 관리되는 복사본을 돌려준다. 원본에 전이를 적용하면 dirty checking 대상이 아니라 조용히 사라진다.
            Payment saved = payments.saveAndFlush(payment);
            histories.save(new PaymentStatusHistory(
                    saved.paymentId(), null, PaymentStatus.RECEIVED, "test-fixture", "접수", ids.now()));

            stateMachine.transition(saved, PaymentStatus.VALIDATED, "test-fixture", null);
            outbox.append(
                    saved.paymentId(),
                    PaymentEventType.PAYMENT_VALIDATED,
                    new PaymentValidatedEvent(
                            saved.paymentId(),
                            saved.endToEndId(),
                            saved.debtorAccount(),
                            saved.creditorAccount(),
                            saved.creditorBank(),
                            saved.amount(),
                            saved.currency(),
                            saved.remittanceInfo(),
                            ids.now()));
            return saved;
        });
    }

    protected PaymentStatus statusOf(String paymentId) {
        return payments.findById(paymentId).orElseThrow().status();
    }

    protected List<PaymentStatusHistory> historyOf(String paymentId) {
        return histories.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId);
    }

    protected long sentPacs008Count(String paymentId) {
        return clearingLogs.countByPaymentIdAndMsgTypeAndDirection(paymentId, ClearingMsgType.PACS_008, Direction.OUT);
    }

    protected long sentPacs028Count(String paymentId) {
        return clearingLogs.countByPaymentIdAndMsgTypeAndDirection(paymentId, ClearingMsgType.PACS_028, Direction.OUT);
    }

    protected List<ClearingMessageLog> clearingLogOf(String paymentId) {
        return clearingLogs.findByPaymentIdOrderBySentAtAsc(paymentId);
    }

    protected List<OutboxEvent> outboxOf(String paymentId, String eventType) {
        return outboxEvents.findAll().stream()
                .filter(e -> e.aggregateId().equals(paymentId) && e.eventType().equals(eventType))
                .toList();
    }
}
