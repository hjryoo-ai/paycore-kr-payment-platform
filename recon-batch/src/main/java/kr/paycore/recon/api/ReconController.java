package kr.paycore.recon.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kr.paycore.core.recon.BreakStatus;
import kr.paycore.core.recon.ReconBreak;
import kr.paycore.core.recon.ReconBreakRepository;
import kr.paycore.recon.match.ReconService;
import kr.paycore.recon.match.ReconSummary;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대사 운영 API (docs §5.6 — 결과는 테이블과 리포트로 남고 대시보드가 이걸 읽는다). */
@RestController
@RequestMapping("/api/v1/recon")
public class ReconController {

    private final ReconService reconService;
    private final ReconBreakRepository breaks;
    private final Clock clock;

    public ReconController(ReconService reconService, ReconBreakRepository breaks, Clock clock) {
        this.reconService = reconService;
        this.breaks = breaks;
        this.clock = clock;
    }

    /** 대사 실행. 날짜를 생략하면 오늘(기준 시간대)을 대사한다. */
    @PostMapping("/run")
    public ReconSummary run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconService.run(date == null ? LocalDate.now(clock) : date);
    }

    public record BreakView(Long breakId, String paymentId, String breakType, String detail, String status) {}

    @GetMapping("/breaks")
    public List<BreakView> breaks(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) BreakStatus status) {
        List<ReconBreak> found = status == null
                ? breaks.findByReconDateOrderByBreakTypeAscBreakIdAsc(date)
                : breaks.findByReconDateAndStatusOrderByBreakTypeAscBreakIdAsc(date, status);
        return found.stream()
                .map(b -> new BreakView(
                        b.breakId(),
                        b.paymentId(),
                        b.breakType().name(),
                        b.detail(),
                        b.status().name()))
                .toList();
    }
}
