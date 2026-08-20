package kr.paycore.simulator.clearing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.common.clearing.Pacs008;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.simulator.mode.ModeSettings;
import kr.paycore.simulator.mode.SimulatorMode;
import kr.paycore.simulator.support.AbstractSimulatorIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 장애 주입 모드별 동작 (docs §5.4, ADR-0009). */
class SimulatorModeIT extends AbstractSimulatorIT {

    @Test
    @DisplayName("NORMAL — pacs.008 을 받으면 ACSC 로 응답하고 처리 기록을 남긴다")
    void normalMode() {
        String e2e = newEndToEndId();
        Pacs008 request = creditTransfer(e2e, 1_500_000L);

        sendRequest(request, ClearingMsgType.PACS_008);
        Pacs002 response = receiveResponse();

        assertThat(response.status()).isEqualTo(TxSts.ACSC);
        assertThat(response.endToEndId()).isEqualTo(e2e);
        assertThat(response.txInfAndSts().orgnlMsgId()).isEqualTo(request.msgId());
        assertThat(response.answersInquiry()).isFalse();
        assertThat(store.find(e2e)).isPresent();
        assertThat(store.find(e2e).orElseThrow().amount()).isEqualTo(1_500_000L);
    }

    @Test
    @DisplayName("같은 endToEndId 를 두 번 받으면 두 번째는 DUPL 로 거절한다 — 청산망의 중복 방어")
    void rejectsDuplicateEndToEndId() {
        String e2e = newEndToEndId();

        sendRequest(creditTransfer(e2e, 1_000_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse().status()).isEqualTo(TxSts.ACSC);

        // 같은 이체를 새 msgId 로 다시 보낸다 — 재송신 상황이다.
        sendRequest(creditTransfer(e2e, 1_000_000L), ClearingMsgType.PACS_008);
        Pacs002 second = receiveResponse();

        assertThat(second.status()).isEqualTo(TxSts.RJCT);
        assertThat(second.txInfAndSts().stsRsn()).isEqualTo(StsRsn.DUPL);
        // 돈이 두 번 나가지 않았다는 증거: 처리 기록은 여전히 1건이다.
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("REJECT — 지정한 사유 코드로 RJCT 응답한다")
    void rejectMode() {
        applyMode(new ModeSettings(SimulatorMode.REJECT, Duration.ofSeconds(1), StsRsn.AM04, 2));
        String e2e = newEndToEndId();

        sendRequest(creditTransfer(e2e, 9_000_000L), ClearingMsgType.PACS_008);
        Pacs002 response = receiveResponse();

        assertThat(response.status()).isEqualTo(TxSts.RJCT);
        assertThat(response.txInfAndSts().stsRsn()).isEqualTo(StsRsn.AM04);
        assertThat(store.find(e2e).orElseThrow().status()).isEqualTo(TxSts.RJCT);
    }

    @Test
    @DisplayName("PROCESS_BUT_NO_RESPONSE — 응답은 없지만 처리 기록은 남는다. 상태조회는 ACSC 로 답한다")
    void processButNoResponse() {
        applyMode(new ModeSettings(SimulatorMode.PROCESS_BUT_NO_RESPONSE, Duration.ofSeconds(1), StsRsn.AM04, 2));
        String e2e = newEndToEndId();
        Pacs008 request = creditTransfer(e2e, 2_000_000L);

        sendRequest(request, ClearingMsgType.PACS_008);

        assertThat(receiveResponse(Duration.ofSeconds(2))).as("응답이 유실되어야 한다").isNull();
        // 그런데 돈은 나갔다 — 이것이 시나리오 #2 가 어려운 이유다.
        assertThat(store.find(e2e)).isPresent();

        sendRequest(inquiry(e2e, request.msgId()), ClearingMsgType.PACS_028);
        Pacs002 answer = receiveResponse();

        assertThat(answer.answersInquiry()).isTrue();
        assertThat(answer.status()).isEqualTo(TxSts.ACSC);
    }

    @Test
    @DisplayName("DROP_REQUEST — 이체 지시를 유실시키고, 상태조회에는 NOOR(받은 적 없음)로 답한다")
    void dropRequestMode() {
        applyMode(new ModeSettings(SimulatorMode.DROP_REQUEST, Duration.ofSeconds(1), StsRsn.AM04, 2));
        String e2e = newEndToEndId();
        Pacs008 request = creditTransfer(e2e, 3_000_000L);

        sendRequest(request, ClearingMsgType.PACS_008);

        assertThat(receiveResponse(Duration.ofSeconds(2))).isNull();
        assertThat(store.find(e2e)).as("아무 기록도 남지 않아야 한다").isEmpty();

        sendRequest(inquiry(e2e, request.msgId()), ClearingMsgType.PACS_028);
        Pacs002 answer = receiveResponse();

        assertThat(answer.status()).isEqualTo(TxSts.RJCT);
        assertThat(answer.txInfAndSts().stsRsn()).isEqualTo(StsRsn.NOOR);
        assertThat(answer.answersInquiry()).isTrue();
    }

    @Test
    @DisplayName("DUPLICATE_RESPONSE — 동일한 pacs.002 를 두 번 보낸다 (msgId 까지 같다)")
    void duplicateResponseMode() {
        applyMode(new ModeSettings(SimulatorMode.DUPLICATE_RESPONSE, Duration.ofSeconds(1), StsRsn.AM04, 2));
        String e2e = newEndToEndId();

        sendRequest(creditTransfer(e2e, 1_200_000L), ClearingMsgType.PACS_008);

        Pacs002 first = receiveResponse();
        Pacs002 second = receiveResponse();

        assertThat(first).isEqualTo(second);
        assertThat(first.msgId()).isEqualTo(second.msgId());
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELAY — 응답이 지연되지만 큐 소비는 멈추지 않는다")
    void delayMode() {
        applyMode(new ModeSettings(SimulatorMode.DELAY, Duration.ofMillis(800), StsRsn.AM04, 2));
        String e2e = newEndToEndId();

        sendRequest(creditTransfer(e2e, 1_100_000L), ClearingMsgType.PACS_008);

        assertThat(receiveResponse(Duration.ofMillis(300)))
                .as("지연 시간 안에는 응답이 없어야 한다")
                .isNull();
        assertThat(receiveResponse(Duration.ofSeconds(5))).isNotNull();
    }

    @Test
    @DisplayName("OUT_OF_ORDER — 모아둔 응답을 역순으로 내보낸다")
    void outOfOrderMode() {
        applyMode(new ModeSettings(SimulatorMode.OUT_OF_ORDER, Duration.ofSeconds(1), StsRsn.AM04, 2));
        String first = newEndToEndId();
        String second = newEndToEndId();

        sendRequest(creditTransfer(first, 1_000_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse(Duration.ofMillis(300))).as("버퍼가 찰 때까지 보류된다").isNull();

        sendRequest(creditTransfer(second, 2_000_000L), ClearingMsgType.PACS_008);

        Pacs002 firstOut = receiveResponse();
        Pacs002 secondOut = receiveResponse();

        assertThat(firstOut.endToEndId()).isEqualTo(second);
        assertThat(secondOut.endToEndId()).isEqualTo(first);
    }

    @Test
    @DisplayName("알 수 없는 msgType 은 폐기하고 응답하지 않는다 — poison message 가 큐를 막지 않는다")
    void discardsUnknownMessageType() {
        sendRequest(creditTransfer(newEndToEndId(), 1_000_000L), "pacs.999");

        assertThat(receiveResponse(Duration.ofSeconds(2))).isNull();
        assertThat(store.size()).isZero();

        // 그리고 그 다음 정상 메시지는 정상 처리된다.
        String e2e = newEndToEndId();
        sendRequest(creditTransfer(e2e, 1_000_000L), ClearingMsgType.PACS_008);
        assertThat(receiveResponse().status()).isEqualTo(TxSts.ACSC);
    }
}
