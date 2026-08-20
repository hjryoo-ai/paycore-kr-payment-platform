package kr.paycore.simulator.eod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.paycore.simulator.clearing.ProcessedTransfer;
import kr.paycore.simulator.clearing.TransferStore;
import kr.paycore.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 일마감 파일 생성 (docs §5.4, §5.6).
 *
 * <p>이 CSV 가 대사(3-way match)에서 <b>"청산망이 아는 것"</b> 쪽 증빙이다. 우리 DB 를 보고 만드는 게
 * 아니라 시뮬레이터가 자기 기록만으로 만든다 — 그래야 대사가 의미를 갖는다.
 */
@Service
public class EodService {

    private static final Logger log = LoggerFactory.getLogger(EodService.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final String HEADER =
            "endToEndId,msgId,txId,debtorAccount,creditorAccount,creditorBank,amount,currency,status,reason,processedAt";

    private final TransferStore store;
    private final SimulatorProperties properties;
    private final Clock clock;

    public EodService(TransferStore store, SimulatorProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public record EodResult(LocalDate date, String file, int count) {}

    /** 해당 업무일자의 처리 내역을 CSV 로 만들어 파일로 남기고 결과를 돌려준다. */
    public EodResult generate(LocalDate date) {
        LocalDate businessDate = date == null ? LocalDate.now(clock) : date;
        String csv = render(businessDate);
        Path file = Path.of(properties.eodDir()).resolve("clearing-eod-" + businessDate.format(FILE_DATE) + ".csv");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("EOD 파일 생성 실패: " + file, e);
        }
        int count = transfersOn(businessDate).size();
        log.info("EOD 생성 date={} count={} file={}", businessDate, count, file.toAbsolutePath());
        return new EodResult(businessDate, file.toAbsolutePath().toString(), count);
    }

    /** 파일을 만들지 않고 내용만 돌려준다 — recon-batch 가 HTTP 로 가져갈 때 쓴다. */
    public String render(LocalDate date) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (ProcessedTransfer t : transfersOn(date)) {
            sb.append(csv(t.endToEndId()))
                    .append(',')
                    .append(csv(t.msgId()))
                    .append(',')
                    .append(csv(t.txId()))
                    .append(',')
                    .append(csv(t.debtorAccount()))
                    .append(',')
                    .append(csv(t.creditorAccount()))
                    .append(',')
                    .append(csv(t.creditorBank()))
                    .append(',')
                    .append(t.amount())
                    .append(',')
                    .append(csv(t.currency()))
                    .append(',')
                    .append(t.status())
                    .append(',')
                    .append(t.reason() == null ? "" : t.reason().name())
                    .append(',')
                    .append(t.processedAt())
                    .append('\n');
        }
        return sb.toString();
    }

    private List<ProcessedTransfer> transfersOn(LocalDate date) {
        ZoneId zone = clock.getZone();
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        return store.processedBetween(from, to);
    }

    /**
     * 계좌번호에 콤마가 들어갈 일은 없지만, CSV 를 만들 때 이스케이프를 생략하는 습관이
     * 나중에 다른 필드에서 대사를 조용히 깨뜨린다.
     */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
