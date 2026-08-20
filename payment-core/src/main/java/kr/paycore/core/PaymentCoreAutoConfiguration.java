package kr.paycore.core;

import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import kr.paycore.common.id.Ids;
import kr.paycore.core.config.PaymentCoreProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * payment-core 는 실행 가능한 서비스가 아니라 라이브러리다(ADR-0003). 이를 패키징한 애플리케이션이
 * 자기 베이스 패키지만 스캔해도 core 의 빈/엔티티/리포지토리가 등록되도록 자동설정으로 노출한다.
 *
 * <p>등록 위치: {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 */
@AutoConfiguration
@ComponentScan(basePackages = "kr.paycore.core")
// 엔티티/리포지토리는 domain 외에 outbox, limit 패키지에도 있다.
@EntityScan(basePackages = "kr.paycore.core")
@EnableJpaRepositories(basePackages = "kr.paycore.core")
@EnableTransactionManagement
@EnableConfigurationProperties(PaymentCoreProperties.class)
@EnableScheduling
@EnableAsync
public class PaymentCoreAutoConfiguration {

    /**
     * 접수 후 검증을 돌리는 전용 풀. 무한 큐를 쓰지 않는 이유: 큐가 무한이면 downstream 이 느려질 때
     * 메모리에 작업이 쌓이다 OOM 으로 죽고, 그 순간 큐에 있던 건들이 통째로 사라진다.
     * 큐가 차면 호출 스레드에서 직접 실행({@code CallerRunsPolicy})해 자연스러운 역압을 만든다.
     */
    @Bean("paycoreTaskExecutor")
    @ConditionalOnMissingBean(name = "paycoreTaskExecutor")
    public Executor paycoreTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("paycore-core-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 모든 시간 로직은 주입된 Clock 을 쓴다(CLAUDE.md). 테스트에서 {@code Clock.fixed} 로 바꿔치기하기 위함이며,
     * 결제 시스템에서 "언제"는 대사·시효·마감의 기준이라 재현 불가능한 시간은 그 자체가 버그다.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock paycoreClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    @ConditionalOnMissingBean
    public Ids paycoreIds(Clock clock) {
        return new Ids(clock);
    }
}
