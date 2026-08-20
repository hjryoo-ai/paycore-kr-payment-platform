package kr.paycore.recon.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.recon.config.ReconProperties;
import kr.paycore.recon.match.LedgerSnapshot;
import kr.paycore.recon.match.PaymentSnapshot;
import kr.paycore.recon.match.ReconFinding;
import kr.paycore.recon.source.ClearingEodRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 대사 요약 리포트 (docs §5.6).
 *
 * <p>사람이 읽는 산출물이다. 그래서 숫자만 늘어놓지 않고 <b>양쪽이 각각 무엇을 주장했는지</b>를 적는다.
 * 불일치 목록만 있는 리포트는 "그래서 어디부터 보라는 건가"라는 질문을 남긴다.
 *
 * <p>계좌번호는 리포트에서도 마스킹한다 — 리포트는 메신저로 공유되기 가장 쉬운 산출물이다.
 */
@Component
public class ReconReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReconReportWriter.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReconProperties properties;

    public ReconReportWriter(ReconProperties properties) {
        this.properties = properties;
    }

    public String write(
            LocalDate reconDate,
            List<PaymentSnapshot> payments,
            List<ClearingEodRecord> clearingRecords,
            List<LedgerSnapshot> ledgers,
            List<ReconFinding> findings,
            Instant executedAt) {

        Path file = Path.of(properties.reportDir()).resolve("recon-" + reconDate.format(FILE_DATE) + ".md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    render(reconDate, payments, clearingRecords, ledgers, findings, executedAt),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("대사 리포트를 쓰지 못했다: " + file, e);
        }
        log.info("대사 리포트 생성 {}", file.toAbsolutePath());
        return file.toAbsolutePath().toString();
    }

    String render(
            LocalDate reconDate,
            List<PaymentSnapshot> payments,
            List<ClearingEodRecord> clearingRecords,
            List<LedgerSnapshot> ledgers,
            List<ReconFinding> findings,
            Instant executedAt) {

        Map<String, Integer> byType = new LinkedHashMap<>();
        findings.forEach(f -> byType.merge(f.type().name(), 1, Integer::sum));

        StringBuilder md = new StringBuilder();
        md.append("# 일마감 대사 리포트 — ").append(reconDate).append('\n').append('\n');
        md.append("실행 시각: `")
                .append(executedAt)
                .append("` · 기준 시간대: `")
                .append(properties.zone())
                .append("`\n\n");

        md.append("## 대사 대상\n\n");
        md.append("| 출처 | 무엇을 아는가 | 건수 |\n|---|---|---|\n");
        md.append("| PAYMENT | 우리가 아는 것 | ").append(payments.size()).append(" |\n");
        md.append("| 청산망 EOD | 청산망이 아는 것 | ").append(clearingRecords.size()).append(" |\n");
        md.append("| LEDGER | 회계가 아는 것 | ").append(ledgers.size()).append(" |\n\n");

        if (findings.isEmpty()) {
            md.append("## 결과\n\n**불일치 없음.** 세 출처의 주장이 모두 일치한다.\n");
            return md.toString();
        }

        md.append("## 불일치 요약\n\n| 유형 | 건수 | 의미 |\n|---|---|---|\n");
        byType.forEach((type, count) -> md.append("| `")
                .append(type)
                .append("` | ")
                .append(count)
                .append(" | ")
                .append(meaning(type))
                .append(" |\n"));

        md.append("\n## 불일치 상세\n\n| 유형 | paymentId | endToEndId | 내용 |\n|---|---|---|---|\n");
        for (ReconFinding f : findings) {
            md.append("| `")
                    .append(f.type())
                    .append("` | `")
                    .append(f.paymentId() == null ? "—" : f.paymentId())
                    .append("` | `")
                    .append(f.key())
                    .append("` | ")
                    .append(escapePipes(f.detail()))
                    .append(" |\n");
        }

        md.append("\n## 조사 순서 제안\n\n");
        // 번호는 실제로 출력되는 항목에만 붙인다. 유형을 건너뛰면서 고정 번호를 쓰면
        // "1. ... 3. ..." 처럼 빠진 자리가 생겨 리포트가 잘못된 것처럼 보인다.
        int order = 1;
        for (String type : INVESTIGATION_ORDER) {
            if (byType.containsKey(type)) {
                md.append(order++)
                        .append(". `")
                        .append(type)
                        .append("` — ")
                        .append(guidance(type))
                        .append('\n');
            }
        }

        md.append("\n## 표본\n\n계좌번호는 마스킹되어 있다.\n\n");
        payments.stream().limit(5).forEach(p -> md.append("- `")
                .append(p.paymentId())
                .append("` ")
                .append(p.status())
                .append(' ')
                .append(p.amount())
                .append("원 출금=")
                .append(AccountMasker.mask(p.debtorAccount()))
                .append('\n'));
        return md.toString();
    }

    /** 조사 우선순위. 돈이 실제로 움직인 쪽부터 본다. */
    private static final List<String> INVESTIGATION_ORDER =
            List.of("MISSING_AT_CLEARING", "STATUS_MISMATCH", "MISSING_AT_US", "LEDGER_MISMATCH", "AMOUNT_MISMATCH");

    private static String guidance(String type) {
        return switch (type) {
            case "MISSING_AT_CLEARING" -> "우리는 돈이 나갔다고 아는데 청산망이 모르는 건이다. 가장 먼저 본다.";
            case "STATUS_MISMATCH" -> "양쪽이 같은 이체를 두고 결과를 다르게 말한다. 청산망 원장을 직접 확인해야 한다.";
            case "MISSING_AT_US" -> "대개 `UNKNOWN` 으로 방치된 건이다. pacs.028 조회 이력을 먼저 확인한다.";
            case "LEDGER_MISMATCH" -> "돈의 이동보다 기록의 문제일 가능성이 높다. 분개 누락/중복을 본다.";
            case "AMOUNT_MISMATCH" -> "금액이 다르다. 접수 금액과 pacs.008 송신 원문을 대조한다.";
            default -> "확인이 필요하다.";
        };
    }

    private static String meaning(String type) {
        return switch (type) {
            case "MISSING_AT_CLEARING" -> "우리는 지급 완료로 아는데 청산망 파일에 없음";
            case "MISSING_AT_US" -> "청산망에는 결론이 있는데 우리는 미확정";
            case "AMOUNT_MISMATCH" -> "양쪽이 아는 금액이 다름";
            case "LEDGER_MISMATCH" -> "결제 상태와 원장이 어긋남";
            case "STATUS_MISMATCH" -> "양쪽이 결과를 다르게 말함";
            default -> "—";
        };
    }

    private static String escapePipes(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }
}
