package kr.paycore.gateway.support;

import java.time.Duration;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.simulator.ClearingSimulatorApplication;
import kr.paycore.simulator.clearing.TransferStore;
import kr.paycore.simulator.mode.ModeSettings;
import kr.paycore.simulator.mode.ModeState;
import kr.paycore.simulator.mode.SimulatorMode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jms.config.JmsListenerEndpointRegistry;

/**
 * 시뮬레이터를 <b>별도 스프링 컨텍스트</b>로 같은 JVM 에 띄운다.
 *
 * <p>게이트웨이 테스트가 진짜로 증명해야 하는 것은 "우리 코드가 실제 상대방과 메시지를 주고받으며
 * 올바른 상태로 수렴하는가"다. 상대방을 목으로 두면 그 목이 곧 우리의 가정이 되어, 가정이 틀렸을 때
 * 테스트도 같이 틀린다. 그래서 실물 시뮬레이터를 실물 Artemis 위에 띄운다.
 */
public final class SimulatorProcess {

    public static final String REQUEST_QUEUE = "GW.TEST.CLR.REQ";
    public static final String RESPONSE_QUEUE = "GW.TEST.CLR.RES";

    private static final ConfigurableApplicationContext CONTEXT;

    static {
        CONTEXT = new SpringApplicationBuilder(ClearingSimulatorApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                // 반드시 커맨드라인 인자로 넘긴다. SpringApplicationBuilder.properties() 는
                // defaultProperties, 즉 <b>가장 낮은 우선순위</b>다. 두 모듈의 application.yml 이
                // 클래스패스 루트에 같은 이름으로 놓여 있어서, 낮은 우선순위로 주면 시뮬레이터 컨텍스트가
                // 게이트웨이의 설정(특히 브로커 주소)을 읽어 엉뚱한 곳에 붙는다.
                .run(
                        // 설정 파일 자체를 로드하지 않는다 — 필요한 값은 아래에서 전부 명시한다.
                        "--spring.config.name=clearing-simulator-it",
                        // 시뮬레이터는 '상대편'이다. 우리 DB·상태머신·아웃박스를 알아서는 안 된다.
                        // 같은 JVM 이라 payment-core 와 JPA/Flyway 가 클래스패스에 딸려 오지만,
                        // 시뮬레이터 컨텍스트에서는 전부 꺼서 실제 배포 형태(웹+JMS 뿐)와 같게 만든다.
                        "--spring.autoconfigure.exclude="
                                + String.join(
                                        ",",
                                        "kr.paycore.core.PaymentCoreAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
                                        "org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration",
                                        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                                        "org.springframework.boot.flyway.autoconfigure.FlywayEndpointAutoConfiguration",
                                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                                        "org.springframework.boot.hibernate.autoconfigure.metrics.HibernateMetricsAutoConfiguration",
                                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                                        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                                        "org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration"),
                        "--spring.application.name=clearing-simulator-under-test",
                        "--spring.main.allow-bean-definition-overriding=false",
                        "--spring.artemis.mode=native",
                        "--spring.artemis.broker-url=" + SharedContainers.ARTEMIS.getBrokerUrl(),
                        "--spring.artemis.user=" + SharedContainers.ARTEMIS.getUser(),
                        "--spring.artemis.password=" + SharedContainers.ARTEMIS.getPassword(),
                        "--paycore.simulator.request-queue=" + REQUEST_QUEUE,
                        "--paycore.simulator.response-queue=" + RESPONSE_QUEUE,
                        "--paycore.simulator.eod-dir=build/test-eod-gateway",
                        "--paycore.simulator.out-of-order-batch=2",
                        "--paycore.simulator.out-of-order-max-hold=1s",
                        "--logging.level.kr.paycore.simulator=DEBUG");
        Runtime.getRuntime().addShutdownHook(new Thread(CONTEXT::close));
    }

    private SimulatorProcess() {}

    public static void start() {
        // 정적 초기화를 강제하기 위한 진입점.
    }

    public static ModeState modes() {
        return CONTEXT.getBean(ModeState.class);
    }

    public static TransferStore transfers() {
        return CONTEXT.getBean(TransferStore.class);
    }

    public static void mode(SimulatorMode mode) {
        mode(mode, Duration.ofSeconds(1), StsRsn.AM04);
    }

    public static void mode(SimulatorMode mode, Duration delay, StsRsn rejectReason) {
        modes().apply(new ModeSettings(mode, delay, rejectReason, 2));
        applyConsumption(mode);
    }

    /**
     * 상태 초기화. 큐를 비운 <b>뒤에</b> 호출해야 한다 — 앞 테스트가 남긴 메시지가 소비되면서
     * 기록이 되살아나면, 다음 테스트의 "이체는 정확히 1건" 단정이 조용히 거짓이 된다.
     */
    public static void reset() {
        transfers().clear();
        modes().reset();
        startConsuming();
    }

    public static void stopConsuming() {
        requestContainer().ifPresent(c -> {
            if (c.isRunning()) {
                c.stop();
            }
        });
    }

    public static void startConsuming() {
        requestContainer().ifPresent(c -> {
            if (!c.isRunning()) {
                c.start();
            }
        });
    }

    /** DOWN 모드의 리스너 정지/재개. 운영 API 와 같은 동작을 컨텍스트 안에서 직접 수행한다. */
    private static void applyConsumption(SimulatorMode mode) {
        if (mode == SimulatorMode.DOWN) {
            stopConsuming();
        } else {
            startConsuming();
        }
    }

    private static java.util.Optional<org.springframework.jms.listener.MessageListenerContainer> requestContainer() {
        return java.util.Optional.ofNullable(CONTEXT.getBean(JmsListenerEndpointRegistry.class)
                .getListenerContainer(kr.paycore.simulator.clearing.ClearingRequestListener.LISTENER_ID));
    }
}
