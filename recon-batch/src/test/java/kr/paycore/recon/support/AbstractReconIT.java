package kr.paycore.recon.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kr.paycore.common.id.Ids;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.ledger.DrCr;
import kr.paycore.core.ledger.Journal;
import kr.paycore.core.ledger.JournalRepository;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.core.ledger.LedgerEntryRepository;
import kr.paycore.core.recon.ReconBreakRepository;
import kr.paycore.core.statemachine.PaymentStateMachine;
import kr.paycore.recon.config.ReconProperties;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대사 통합 테스트 기반.
 *
 * <p>EOD 는 파일로 놓는다. HTTP 경로 대신 파일 경로를 쓰는 이유는, 여기서 검증하려는 것이
 * "파일을 어떻게 가져오는가"가 아니라 "가져온 뒤 무엇을 판정하는가"이기 때문이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractReconIT {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void infraProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.ORACLE::getUsername);
        registry.add("spring.datasource.password", SharedContainers.ORACLE::getPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::getBootstrapServers);
    }

    @Autowired
    protected PaymentRepository payments;

    @Autowired
    protected PaymentStatusHistoryRepository histories;

    @Autowired
    protected JournalRepository journals;

    @Autowired
    protected LedgerEntryRepository entries;

    @Autowired
    protected ReconBreakRepository breaks;

    @Autowired
    protected PaymentStateMachine stateMachine;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ReconProperties reconProperties;

    @Autowired
    protected Ids ids;

    @Autowired
    protected Clock clock;

    protected LocalDate today() {
        return LocalDate.now(clock);
    }

    @BeforeEach
    void resetWorld() {
        jdbc.update("DELETE FROM RECON_BREAK");
        jdbc.update("DELETE FROM LEDGER_ENTRY");
        jdbc.update("DELETE FROM JOURNAL");
        jdbc.update("DELETE FROM CLEARING_MESSAGE_LOG");
        jdbc.update("DELETE FROM OUTBOX_EVENT");
        jdbc.update("DELETE FROM PROCESSED_MESSAGE");
        jdbc.update("DELETE FROM PAYMENT_STATUS_HISTORY");
        jdbc.update("DELETE FROM PAYMENT");
        jdbc.update("DELETE FROM DAILY_LIMIT");
        deleteIfExists(eodFile(today()));
    }

    /**
     * 지정한 상태까지 전이시킨 결제를 만든다. 상태는 빌더로 찍지 않고 전이표를 따라 올린다 —
     * 준비 과정에서도 상태머신을 우회하면 "테스트에서만 존재하는 상태"가 생긴다.
     */
    protected Payment givenPayment(PaymentStatus target, long amount) {
        return tx.execute(status -> {
            int n = SEQ.incrementAndGet();
            Payment draft = Payment.builder()
                    .paymentId(ids.newPaymentId())
                    .idempotencyKey("recon-it-" + ids.newEventId())
                    .endToEndId(ids.newEndToEndId())
                    .debtorAccount("110-123-4567" + String.format("%02d", n % 100))
                    .creditorAccount("352-987-6543" + String.format("%02d", n % 100))
                    .creditorBank("088")
                    .amount(amount)
                    .currency("KRW")
                    .remittanceInfo("대사 통합테스트")
                    .status(PaymentStatus.RECEIVED)
                    .createdAt(ids.now())
                    .build();
            // save() 의 반환값을 써야 한다 — PAYMENT 는 merge 로 저장되어 원본이 준영속으로 남는다.
            Payment payment = payments.saveAndFlush(draft);
            histories.save(new PaymentStatusHistory(
                    payment.paymentId(), null, PaymentStatus.RECEIVED, "test-fixture", "접수", ids.now()));
            for (PaymentStatus step : pathTo(target)) {
                stateMachine.transition(payment, step, "test-fixture", null);
            }
            return payment;
        });
    }

    private static List<PaymentStatus> pathTo(PaymentStatus target) {
        List<PaymentStatus> path = new ArrayList<>();
        switch (target) {
            case RECEIVED -> {}
            case REJECTED -> path.add(PaymentStatus.REJECTED);
            case VALIDATED -> path.add(PaymentStatus.VALIDATED);
            case SENT_TO_CLEARING -> path.addAll(List.of(PaymentStatus.VALIDATED, PaymentStatus.SENT_TO_CLEARING));
            case UNKNOWN ->
                path.addAll(List.of(PaymentStatus.VALIDATED, PaymentStatus.SENT_TO_CLEARING, PaymentStatus.UNKNOWN));
            case MANUAL_REVIEW ->
                path.addAll(List.of(
                        PaymentStatus.VALIDATED,
                        PaymentStatus.SENT_TO_CLEARING,
                        PaymentStatus.UNKNOWN,
                        PaymentStatus.MANUAL_REVIEW));
            case FAILED ->
                path.addAll(List.of(PaymentStatus.VALIDATED, PaymentStatus.SENT_TO_CLEARING, PaymentStatus.FAILED));
            case CLEARED ->
                path.addAll(List.of(PaymentStatus.VALIDATED, PaymentStatus.SENT_TO_CLEARING, PaymentStatus.CLEARED));
            case SETTLED ->
                path.addAll(List.of(
                        PaymentStatus.VALIDATED,
                        PaymentStatus.SENT_TO_CLEARING,
                        PaymentStatus.CLEARED,
                        PaymentStatus.SETTLED));
        }
        return path;
    }

    /** 정상적인 분개 1벌을 만든다. */
    protected String givenJournal(Payment payment) {
        return givenJournal(payment, payment.amount(), payment.amount());
    }

    /** 일부러 어긋난 분개도 만들 수 있어야 원장 불일치를 검증할 수 있다. */
    protected String givenJournal(Payment payment, long debit, long credit) {
        return tx.execute(status -> {
            String journalId = ids.newEventId();
            journals.save(new Journal(journalId, payment.paymentId(), ids.now()));
            entries.save(new LedgerEntry(ids.newEventId(), journalId, payment.debtorAccount(), DrCr.D, debit));
            entries.save(
                    new LedgerEntry(ids.newEventId(), journalId, reconProperties.suspenseAccount(), DrCr.C, credit));
            return journalId;
        });
    }

    /** 청산망 EOD 파일 한 줄. */
    public record EodLine(String endToEndId, long amount, String status, String reason) {}

    protected EodLine acsc(Payment payment) {
        return new EodLine(payment.endToEndId(), payment.amount(), "ACSC", null);
    }

    protected EodLine rjct(Payment payment, String reason) {
        return new EodLine(payment.endToEndId(), payment.amount(), "RJCT", reason);
    }

    /** 청산망이 그날 처리했다고 주장하는 내역을 파일로 놓는다. */
    protected Path givenClearingEod(LocalDate date, List<EodLine> lines) {
        StringBuilder csv = new StringBuilder(
                "endToEndId,msgId,txId,debtorAccount,creditorAccount,creditorBank,amount,currency,status,reason,processedAt\n");
        Instant at = date.atTime(9, 0).atZone(clock.getZone()).toInstant();
        for (EodLine line : lines) {
            csv.append(line.endToEndId())
                    .append(",M-")
                    .append(line.endToEndId())
                    .append(",T-")
                    .append(line.endToEndId())
                    .append(",110-123-456789,352-987-654321,088,")
                    .append(line.amount())
                    .append(",KRW,")
                    .append(line.status())
                    .append(',')
                    .append(line.reason() == null ? "" : line.reason())
                    .append(',')
                    .append(at)
                    .append('\n');
        }
        Path file = eodFile(date);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    protected Path eodFile(LocalDate date) {
        return Path.of(reconProperties.eodDir())
                .resolve("clearing-eod-" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                        + ".csv");
    }

    protected String readReport(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
