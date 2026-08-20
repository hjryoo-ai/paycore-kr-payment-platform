package kr.paycore.core.recon;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconBreakRepository extends JpaRepository<ReconBreak, Long> {

    List<ReconBreak> findByReconDateOrderByBreakTypeAscBreakIdAsc(LocalDate reconDate);

    List<ReconBreak> findByReconDateAndStatusOrderByBreakTypeAscBreakIdAsc(LocalDate reconDate, BreakStatus status);

    long countByReconDateAndStatus(LocalDate reconDate, BreakStatus status);

    /**
     * 재실행 시 이전 OPEN 건을 지운다. RESOLVED 는 남긴다 — 운영자가 처리한 기록을 배치가 지우면
     * 같은 건을 매일 다시 조사하게 된다.
     */
    long deleteByReconDateAndStatus(LocalDate reconDate, BreakStatus status);
}
