package kr.paycore.simulator.eod;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.simulator.support.AbstractSimulatorIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;

/**
 * EOD 파일 (docs §5.4, §5.6).
 *
 * <p>이 파일은 대사에서 "청산망이 아는 것"이다. 우리 DB 를 보고 만들지 않기 때문에 대사가 의미를 갖는다.
 */
@AutoConfigureTestRestTemplate
class EodIT extends AbstractSimulatorIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EodService eodService;

    @Test
    @DisplayName("처리한 이체가 EOD CSV 에 한 줄로 남는다")
    void generatesCsvForProcessedTransfers() {
        String e2e = newEndToEndId();
        sendRequest(creditTransfer(e2e, 7_777_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse().status()).isEqualTo(TxSts.ACSC);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        EodService.EodResult result =
                rest.postForObject("/simulator/eod?date=" + today, null, EodService.EodResult.class);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.date()).isEqualTo(today);
        Path file = Path.of(result.file());
        assertThat(file).exists();

        String csv = readString(file);
        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo(EodService.HEADER);
        assertThat(csv).contains(e2e).contains("7777000").contains("ACSC");
        assertThat(csv.lines().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("EOD 는 HTTP 로도 가져갈 수 있다 — recon-batch 가 볼륨 공유 없이 읽는다")
    void servesCsvOverHttp() {
        String e2e = newEndToEndId();
        sendRequest(creditTransfer(e2e, 1_234_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse().status()).isEqualTo(TxSts.ACSC);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        String csv = rest.getForObject("/simulator/eod/" + today, String.class);

        assertThat(csv).contains(EodService.HEADER).contains(e2e).contains("1234000");
    }

    @Test
    @DisplayName("처리 내역이 없는 날짜는 헤더만 있는 파일이 된다 — 파일이 없는 것과 0건은 다르다")
    void emptyDayStillProducesHeader() {
        String csv = eodService.render(LocalDate.of(2020, 1, 1));

        assertThat(csv).isEqualTo(EodService.HEADER + "\n");
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
