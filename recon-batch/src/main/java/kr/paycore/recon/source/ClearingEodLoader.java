package kr.paycore.recon.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.paycore.recon.config.ReconProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 청산망 EOD 파일을 확보한다 (docs §5.6).
 *
 * <p>공유 볼륨에 파일이 있으면 그것을 읽고, 없으면 시뮬레이터에서 HTTP 로 받아 같은 자리에 남긴다.
 * 두 경로를 모두 두는 이유는 배포 형태에 대사가 끌려다니지 않게 하기 위해서다 — 볼륨을 공유하든
 * 안 하든 마감은 돌아야 한다.
 *
 * <p>받아 온 원본을 반드시 파일로 남긴다. 대사 결과에 이의가 제기됐을 때 "그때 그 파일"이 없으면
 * 아무것도 증명할 수 없다.
 */
@Component
public class ClearingEodLoader {

    private static final Logger log = LoggerFactory.getLogger(ClearingEodLoader.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient eodRestClient;
    private final ReconProperties properties;

    public ClearingEodLoader(RestClient eodRestClient, ReconProperties properties) {
        this.eodRestClient = eodRestClient;
        this.properties = properties;
    }

    public List<ClearingEodRecord> load(LocalDate date) {
        Path file = fileFor(date);
        if (Files.isReadable(file)) {
            log.info("EOD 파일 사용 date={} file={}", date, file.toAbsolutePath());
            return EodCsvParser.parse(readFile(file));
        }
        String csv = fetch(date);
        writeFile(file, csv);
        log.info("EOD 다운로드 후 보관 date={} file={}", date, file.toAbsolutePath());
        return EodCsvParser.parse(csv);
    }

    public Path fileFor(LocalDate date) {
        return Path.of(properties.eodDir()).resolve("clearing-eod-" + date.format(FILE_DATE) + ".csv");
    }

    private String fetch(LocalDate date) {
        try {
            String body = eodRestClient
                    .get()
                    .uri("/simulator/eod/{date}", date)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new EodFormatException("청산망이 빈 EOD 를 돌려줬다 date=" + date);
            }
            return body;
        } catch (RestClientException e) {
            // 못 받은 것을 '0건'으로 처리하면 전 건이 MISSING_AT_CLEARING 으로 잡힌다. 마감을 세운다.
            throw new EodFormatException("EOD 를 가져오지 못했다 date=" + date + " — 마감을 진행하지 않는다", e);
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("EOD 파일을 읽지 못했다: " + file, e);
        }
    }

    private static void writeFile(Path file, String csv) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("EOD 파일을 저장하지 못했다: " + file, e);
        }
    }
}
