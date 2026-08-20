package kr.paycore.simulator.clearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.simulator.mode.SimulatorController;
import kr.paycore.simulator.mode.SimulatorMode;
import kr.paycore.simulator.support.AbstractSimulatorIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;

/** 운영 API (docs §5.4). 시나리오를 재현 가능하게 만드는 것이 이 API 의 존재 이유다. */
@AutoConfigureTestRestTemplate
class SimulatorApiIT extends AbstractSimulatorIT {

    /** {@code /simulator/transfers} 응답 형태. */
    record TransfersView(int count, List<ProcessedTransfer> transfers) {}

    @Autowired
    private TestRestTemplate rest;

    private SimulatorController.ModeView currentMode() {
        return rest.getForObject("/simulator/mode", SimulatorController.ModeView.class);
    }

    private void putMode(Map<String, Object> body) {
        rest.put("/simulator/mode", body);
    }

    @Test
    @DisplayName("모드 조회/변경/초기화가 동작한다")
    void modeLifecycle() {
        assertThat(currentMode().mode()).isEqualTo(SimulatorMode.NORMAL);
        assertThat(currentMode().consuming()).isTrue();

        putMode(Map.of("mode", "REJECT", "rejectReason", "AC04"));

        assertThat(currentMode().mode()).isEqualTo(SimulatorMode.REJECT);
        assertThat(currentMode().rejectReason()).isEqualTo(StsRsn.AC04);

        rest.postForObject("/simulator/reset", null, SimulatorController.ModeView.class);
        assertThat(currentMode().mode()).isEqualTo(SimulatorMode.NORMAL);
    }

    @Test
    @DisplayName("DOWN 은 큐 소비를 멈추고, 복구하면 쌓여 있던 메시지를 처리한다")
    void downModeStopsAndResumesConsumption() {
        putMode(Map.of("mode", "DOWN"));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(currentMode().consuming()).isFalse());

        String e2e = newEndToEndId();
        sendRequest(creditTransfer(e2e, 1_700_000L), ClearingMsgType.PACS_008);

        assertThat(receiveResponse(Duration.ofSeconds(2)))
                .as("소비가 멈춰 있으므로 응답이 없다")
                .isNull();
        assertThat(store.size()).isZero();

        putMode(Map.of("mode", "NORMAL"));

        // 큐에 남아 있던 메시지가 복구와 함께 처리된다 — DOWN 은 유실이 아니라 지연이다(ADR-0009).
        assertThat(receiveResponse(Duration.ofSeconds(15)).status()).isEqualTo(TxSts.ACSC);
        assertThat(store.find(e2e)).isPresent();
    }

    @Test
    @DisplayName("처리 내역 조회 API 로 청산망이 아는 사실을 확인할 수 있다")
    void transfersApi() {
        String e2e = newEndToEndId();
        sendRequest(creditTransfer(e2e, 4_300_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse().status()).isEqualTo(TxSts.ACSC);

        TransfersView all = rest.getForObject("/simulator/transfers", TransfersView.class);
        assertThat(all.count()).isEqualTo(1);
        assertThat(all.transfers()).singleElement().satisfies(t -> {
            assertThat(t.endToEndId()).isEqualTo(e2e);
            assertThat(t.amount()).isEqualTo(4_300_000L);
            assertThat(t.status()).isEqualTo(TxSts.ACSC);
        });

        assertThat(rest.getForEntity("/simulator/transfers/" + e2e, ProcessedTransfer.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/simulator/transfers/PC-NOT-EXIST", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("REJECT 사유 코드는 API 로 바꿀 수 있다")
    void rejectReasonIsConfigurable() {
        putMode(Map.of("mode", "REJECT", "rejectReason", StsRsn.AC06.name()));

        sendRequest(creditTransfer(newEndToEndId(), 5_000_000L), ClearingMsgType.PACS_008);

        var response = receiveResponse();
        assertThat(response.status()).isEqualTo(TxSts.RJCT);
        assertThat(response.txInfAndSts().stsRsn()).isEqualTo(StsRsn.AC06);
    }
}
