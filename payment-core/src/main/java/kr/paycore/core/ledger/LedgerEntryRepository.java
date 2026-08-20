package kr.paycore.core.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    List<LedgerEntry> findByJournalIdOrderByDrCrAsc(String journalId);

    /** 대사에서 분개 여러 벌의 명세를 한 번에 가져온다 — 건마다 조회하면 N+1 이 된다. */
    List<LedgerEntry> findByJournalIdIn(java.util.Collection<String> journalIds);

    List<LedgerEntry> findByAccountId(String accountId);

    /**
     * 계좌 잔액은 저장하지 않고 <b>명세에서 유도한다</b>(docs §5.5). 잔액 컬럼을 따로 두면 그 컬럼과
     * 명세가 어긋나는 순간 어느 쪽이 진실인지 아무도 모르게 된다.
     */
    @Query("""
            select coalesce(sum(case when e.drCr = kr.paycore.core.ledger.DrCr.D then e.amount else 0 end), 0)
              from LedgerEntry e where e.accountId = :accountId
            """)
    long debitTotal(@Param("accountId") String accountId);

    @Query("""
            select coalesce(sum(case when e.drCr = kr.paycore.core.ledger.DrCr.C then e.amount else 0 end), 0)
              from LedgerEntry e where e.accountId = :accountId
            """)
    long creditTotal(@Param("accountId") String accountId);

    /** 전체 원장의 차변 합 − 대변 합. 항상 0 이어야 한다 — 아니면 복식부기가 깨진 것이다. */
    @Query("""
            select coalesce(sum(case when e.drCr = kr.paycore.core.ledger.DrCr.D then e.amount else -e.amount end), 0)
              from LedgerEntry e
            """)
    long globalImbalance();

    long countByJournalId(String journalId);
}
