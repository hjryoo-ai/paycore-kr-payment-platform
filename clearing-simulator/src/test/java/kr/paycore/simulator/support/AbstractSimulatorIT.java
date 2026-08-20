package kr.paycore.simulator.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Money;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.common.clearing.Pacs008;
import kr.paycore.common.clearing.Pacs028;
import kr.paycore.common.id.Ids;
import kr.paycore.simulator.clearing.TransferStore;
import kr.paycore.simulator.config.SimulatorProperties;
import kr.paycore.simulator.mode.ModeSettings;
import kr.paycore.simulator.mode.ModeState;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;

/**
 * 시뮬레이터 통합 테스트 기반.
 *
 * <p>브로커는 실제 Artemis 컨테이너다 — 인메모리 목으로는 "메시지가 실제로 오갔는가"를 증명하지 못한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractSimulatorIT {

    /** compose 스택과 같은 브로커 버전을 쓴다 (ADR-0002). */
    protected static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.44.0")
            .withUser("paycore")
            .withPassword("paycore");

    static {
        ARTEMIS.start();
    }

    @DynamicPropertySource
    static void artemisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.artemis.broker-url", ARTEMIS::getBrokerUrl);
        registry.add("spring.artemis.user", ARTEMIS::getUser);
        registry.add("spring.artemis.password", ARTEMIS::getPassword);
    }

    @Autowired
    protected JmsTemplate jmsTemplate;

    @Autowired
    protected ClearingMessageCodec codec;

    @Autowired
    protected SimulatorProperties properties;

    @Autowired
    protected ModeState modeState;

    @Autowired
    protected TransferStore store;

    @Autowired
    protected Ids ids;

    @BeforeEach
    void resetSimulator() {
        store.clear();
        modeState.reset();
        drainResponses();
    }

    /** 이전 테스트가 남긴 응답이 다음 테스트의 단정에 섞이지 않게 비운다. */
    protected void drainResponses() {
        jmsTemplate.setReceiveTimeout(200);
        while (jmsTemplate.receive(properties.responseQueue()) != null) {
            // drain
        }
    }

    protected void sendRequest(Object message, String msgType) {
        jmsTemplate.convertAndSend(properties.requestQueue(), codec.encode(message), jms -> {
            jms.setStringProperty("msgType", msgType);
            return jms;
        });
    }

    /** 응답 1건을 기다린다. 지정 시간 안에 오지 않으면 null. */
    protected Pacs002 receiveResponse(Duration timeout) {
        jmsTemplate.setReceiveTimeout(timeout.toMillis());
        Object payload = jmsTemplate.receiveAndConvert(properties.responseQueue());
        return payload == null ? null : codec.decode((String) payload, Pacs002.class);
    }

    protected Pacs002 receiveResponse() {
        Pacs002 response = receiveResponse(Duration.ofSeconds(10));
        assertThat(response).as("pacs.002 응답").isNotNull();
        return response;
    }

    protected void applyMode(ModeSettings settings) {
        modeState.apply(settings);
    }

    protected Pacs008 creditTransfer(String endToEndId, long amount) {
        String msgId = ids.newClearingMsgId();
        return new Pacs008(
                new Pacs008.GrpHdr(msgId, Instant.now(), 1, "020", "088"),
                new Pacs008.CdtTrfTxInf(
                        new Pacs008.PmtId(endToEndId, msgId),
                        Money.krw(amount),
                        "110-123-456789",
                        "020",
                        "352-987-654321",
                        "088",
                        "테스트 이체"));
    }

    protected Pacs028 inquiry(String endToEndId, String orgnlMsgId) {
        return new Pacs028(
                new Pacs028.GrpHdr(ids.newClearingMsgId(), Instant.now()),
                new Pacs028.TxInf(orgnlMsgId, ClearingMsgType.PACS_008, endToEndId, orgnlMsgId));
    }

    protected String newEndToEndId() {
        return ids.newEndToEndId();
    }
}
