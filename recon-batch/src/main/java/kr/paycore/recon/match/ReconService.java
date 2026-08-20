package kr.paycore.recon.match;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.ledger.DrCr;
import kr.paycore.core.ledger.Journal;
import kr.paycore.core.ledger.JournalRepository;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.core.ledger.LedgerEntryRepository;
import kr.paycore.core.recon.BreakStatus;
import kr.paycore.core.recon.ReconBreak;
import kr.paycore.core.recon.ReconBreakRepository;
import kr.paycore.recon.config.ReconProperties;
import kr.paycore.recon.report.ReconReportWriter;
import kr.paycore.recon.source.ClearingEodLoader;
import kr.paycore.recon.source.ClearingEodRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일마감 대사 실행 (docs §5.6).
 *
 * <p>세 출처를 각자 읽어 {@link ReconEngine} 에 넘기고, 결과를 {@code RECON_BREAK} 에 남기고 리포트를 쓴다.
 * 판정 로직이 여기 없는 것이 의도다 — 판정은 순수 함수로 따로 두어야 전수 테스트가 가능하다.
 */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    private final PaymentRepository payments;
    private final JournalRepository journals;
    private final LedgerEntryRepository entries;
    private final ReconBreakRepository breaks;
    private final ClearingEodLoader eodLoader;
    private final ReconReportWriter reportWriter;
    private final ReconProperties properties;
    private final Clock clock;

    public ReconService(
            PaymentRepository payments,
            JournalRepository journals,
            LedgerEntryRepository entries,
            ReconBreakRepository breaks,
            ClearingEodLoader eodLoader,
            ReconReportWriter reportWriter,
            ReconProperties properties,
            Clock clock) {
        this.payments = payments;
        this.journals = journals;
        this.entries = entries;
        this.breaks = breaks;
        this.eodLoader = eodLoader;
        this.reportWriter = reportWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 해당 업무일자를 대사한다.
     *
     * <p>재실행하면 그 날짜의 OPEN 건만 지우고 다시 만든다. RESOLVED 는 남긴다 — 운영자가 처리한
     * 기록을 배치가 지우면 같은 건을 매일 처음부터 다시 조사하게 된다 (ADR-0010).
     */
    @Transactional
    public ReconSummary run(LocalDate reconDate) {
        Instant from = reconDate.atStartOfDay(clock.getZone()).toInstant();
        Instant to = reconDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant();

        List<Payment> ourPayments =
                payments.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to);
        List<ClearingEodRecord> clearingRecords = eodLoader.load(reconDate);
        List<LedgerSnapshot> ledgers = ledgerSnapshots(from, to);

        List<PaymentSnapshot> ourSnapshots = ourPayments.stream()
                .map(p -> new PaymentSnapshot(p.paymentId(), p.endToEndId(), p.status(), p.amount(), p.debtorAccount()))
                .toList();

        ReconEngine engine = new ReconEngine(properties.suspenseAccount());
        List<ReconFinding> findings = engine.reconcile(ourSnapshots, clearingRecords, ledgers);

        long removed = breaks.deleteByReconDateAndStatus(reconDate, BreakStatus.OPEN);
        if (removed > 0) {
            log.info("재실행 — 이전 OPEN 불일치 {}건을 교체한다 date={}", removed, reconDate);
        }
        Instant now = clock.instant();
        breaks.saveAll(findings.stream()
                .map(f -> new ReconBreak(reconDate, f.paymentId(), f.type(), f.detail(), now))
                .toList());

        Map<String, Integer> byType = new LinkedHashMap<>();
        findings.forEach(f -> byType.merge(f.type().name(), 1, Integer::sum));

        String reportFile = reportWriter.write(reconDate, ourSnapshots, clearingRecords, ledgers, findings, now);
        ReconSummary summary = new ReconSummary(
                reconDate,
                ourSnapshots.size(),
                clearingRecords.size(),
                ledgers.size(),
                findings.size(),
                byType,
                reportFile,
                now);

        if (summary.clean()) {
            log.info(
                    "대사 완료 — 불일치 없음 date={} 우리={} 청산망={} 원장={}",
                    reconDate,
                    summary.ourCount(),
                    summary.clearingCount(),
                    summary.ledgerCount());
        } else {
            log.warn("대사 완료 — 불일치 {}건 date={} 유형별={}", summary.openBreaks(), reconDate, byType);
        }
        return summary;
    }

    private List<LedgerSnapshot> ledgerSnapshots(Instant from, Instant to) {
        List<Journal> dayJournals = journals.findByPostedAtGreaterThanEqualAndPostedAtLessThan(from, to);
        if (dayJournals.isEmpty()) {
            return List.of();
        }
        Map<String, List<LedgerEntry>> linesByJournal =
                entries
                        .findByJournalIdIn(
                                dayJournals.stream().map(Journal::journalId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(LedgerEntry::journalId));

        return dayJournals.stream()
                .map(j -> {
                    List<LedgerEntry> lines = linesByJournal.getOrDefault(j.journalId(), List.of());
                    long debit = lines.stream()
                            .filter(e -> e.drCr() == DrCr.D)
                            .mapToLong(LedgerEntry::amount)
                            .sum();
                    long credit = lines.stream()
                            .filter(e -> e.drCr() == DrCr.C)
                            .mapToLong(LedgerEntry::amount)
                            .sum();
                    return new LedgerSnapshot(j.paymentId(), j.journalId(), debit, credit, lines.size());
                })
                .toList();
    }
}
