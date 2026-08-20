package kr.paycore.core.limit;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import kr.paycore.core.config.PaymentCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 일일 한도 차감 (docs §5.2). 호출자의 트랜잭션 안에서 동작한다.
 *
 * <p>업무일자는 시스템 기본 존이 아니라 <b>Clock 의 존</b>으로 계산한다. 서버가 UTC 로 뜨든 KST 로 뜨든
 * '오늘'의 정의가 흔들리면 마감 시각 전후로 한도가 이상하게 리셋된다.
 */
@Service
public class DailyLimitService {

    private static final Logger log = LoggerFactory.getLogger(DailyLimitService.class);

    private final DailyLimitRepository repository;
    private final DailyLimitInitializer initializer;
    private final PaymentCoreProperties properties;
    private final Clock clock;

    public DailyLimitService(
            DailyLimitRepository repository,
            DailyLimitInitializer initializer,
            PaymentCoreProperties properties,
            Clock clock) {
        this.repository = repository;
        this.initializer = initializer;
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate businessDate() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    /**
     * 한도를 차감한다.
     *
     * @return 차감 성공 시 true, 한도 초과로 차감하지 못하면 false
     */
    public boolean tryConsume(String accountId, long amount) {
        LocalDate today = businessDate();
        DailyLimit limit = lockOrCreate(accountId, today);

        if (!limit.canConsume(amount)) {
            log.info("일일 한도 초과 account=**** date={} 잔여={} 요청={}", today, limit.remaining(), amount);
            return false;
        }
        limit.consume(amount, clock.instant());
        repository.save(limit);
        return true;
    }

    public long remaining(String accountId) {
        return repository
                .findByAccountIdAndLimitDate(accountId, businessDate())
                .map(DailyLimit::remaining)
                .orElse(properties.defaultDailyLimit());
    }

    private DailyLimit lockOrCreate(String accountId, LocalDate date) {
        Optional<DailyLimit> found = repository.findForUpdate(accountId, date);
        if (found.isPresent()) {
            return found.get();
        }
        try {
            // 첫 거래라 행이 없다. 생성은 반드시 별도 트랜잭션에서 — 이유는 DailyLimitInitializer 주석 참고.
            initializer.create(accountId, date, properties.defaultDailyLimit(), clock.instant());
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 다른 요청이 먼저 만들었다. 정상 경로다.
            log.debug("한도 행 생성 경합 — 기존 행을 잠근다 date={}", date);
        }
        return repository
                .findForUpdate(accountId, date)
                .orElseThrow(() -> new IllegalStateException("한도 행을 만들지도 찾지도 못했다: date=" + date));
    }
}
