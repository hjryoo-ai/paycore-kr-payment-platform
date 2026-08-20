package kr.paycore.core.limit;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyLimitRepository extends JpaRepository<DailyLimit, DailyLimit.Key> {

    /**
     * 한도 행을 <b>비관적 쓰기 락</b>으로 잡는다 (SELECT ... FOR UPDATE).
     *
     * <p>낙관적 락을 쓰지 않는 이유는 docs/adr/0006 에 적었다. 요지는: 한도 차감은 읽은 값에 기반해
     * 쓰는 전형적인 read-modify-write 이고, 충돌 시 재시도로 풀면 사용자에게는 이유 없는 실패로 보인다.
     */
    Optional<DailyLimit> findByAccountIdAndLimitDate(String accountId, LocalDate limitDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyLimit d where d.accountId = :accountId and d.limitDate = :limitDate")
    Optional<DailyLimit> findForUpdate(@Param("accountId") String accountId, @Param("limitDate") LocalDate limitDate);
}
