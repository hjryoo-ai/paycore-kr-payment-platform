package kr.paycore.core.limit;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그날의 한도 행을 만든다. <b>반드시 별도 트랜잭션(REQUIRES_NEW)</b>이어야 한다.
 *
 * <p>이유: 같은 계좌의 첫 거래 두 건이 동시에 들어오면 한쪽은 PK 충돌(ORA-00001)을 맞는다. 그 INSERT 가
 * 호출자의 트랜잭션 안에서 일어나면 제약 위반이 그 트랜잭션 전체를 rollback-only 로 만들어, 뒤이은
 * 재조회도 못 하고 검증 자체가 통째로 실패한다. 실제로 그렇게 실패했고, 증상은 "한도 초과여야 할 건이
 * VALIDATED 로 나가는 것"이었다 — 조용해서 더 위험한 종류의 버그다.
 *
 * <p>별도 트랜잭션이면 충돌은 그 트랜잭션 안에서 끝나고, 호출자는 예외만 받아 재조회로 넘어갈 수 있다.
 * 접수 경로({@code PaymentIntakeStore})가 UNIQUE 경합을 다루는 방식과 같은 패턴이다.
 */
@Component
public class DailyLimitInitializer {

    private final DailyLimitRepository repository;

    public DailyLimitInitializer(DailyLimitRepository repository) {
        this.repository = repository;
    }

    /** 충돌 시 예외를 그대로 던진다 — 삼키면 이 트랜잭션이 rollback-only 인 채로 커밋되어 더 이상해진다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String accountId, LocalDate date, long limitAmount, Instant now) {
        repository.saveAndFlush(new DailyLimit(accountId, date, limitAmount, now));
    }
}
