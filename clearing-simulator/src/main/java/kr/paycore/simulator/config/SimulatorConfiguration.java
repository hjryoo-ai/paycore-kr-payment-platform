package kr.paycore.simulator.config;

import java.time.Clock;
import java.time.ZoneId;
import kr.paycore.common.clearing.ClearingMessageCodec;
import kr.paycore.common.id.Ids;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfiguration {

    @Bean
    public Clock simulatorClock(SimulatorProperties properties) {
        return Clock.system(ZoneId.of(properties.zone()));
    }

    @Bean
    public Ids simulatorIds(Clock clock) {
        return new Ids(clock);
    }

    @Bean
    public ClearingMessageCodec clearingMessageCodec() {
        return new ClearingMessageCodec();
    }

    /**
     * 지연 응답 전용 스케줄러. DELAY 모드에서 리스너 스레드를 재우면 큐 소비 자체가 멈춰
     * "지연"이 아니라 "정지"를 시뮬레이션하게 된다 — 그건 DOWN 모드의 몫이다.
     */
    @Bean
    public TaskScheduler simulatorScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sim-delay-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
