package kr.paycore.simulator.clearing;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import kr.paycore.common.clearing.ClearingMsgType;
import kr.paycore.common.clearing.Pacs002;
import kr.paycore.common.clearing.Pacs008;
import kr.paycore.common.clearing.Pacs028;
import kr.paycore.common.clearing.StsRsn;
import kr.paycore.common.clearing.TxSts;
import kr.paycore.common.id.Ids;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.simulator.mode.ModeSettings;
import kr.paycore.simulator.mode.ModeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 청산망 + 상대은행의 처리 로직 (docs §5.4).
 *
 * <p>규칙 하나만 지킨다: <b>같은 endToEndId 는 두 번 처리하지 않는다.</b> 나머지는 전부 응답을
 * 어떻게 (혹은 안) 보내느냐의 문제다. 우리 쪽 방어가 무너져 재송신을 하더라도, 이 규칙 때문에
 * 돈은 한 번만 나간다 — §7.3 의 최후 방어선 ①.
 */
@Service
public class ClearingProcessor {

    private static final Logger log = LoggerFactory.getLogger(ClearingProcessor.class);

    private final TransferStore store;
    private final ResponseSender sender;
    private final ModeState modeState;
    private final TaskScheduler scheduler;
    private final Ids ids;
    private final Clock clock;

    public ClearingProcessor(
            TransferStore store,
            ResponseSender sender,
            ModeState modeState,
            TaskScheduler scheduler,
            Ids ids,
            Clock clock) {
        this.store = store;
        this.sender = sender;
        this.modeState = modeState;
        this.scheduler = scheduler;
        this.ids = ids;
        this.clock = clock;
    }

    /** 이체 지시 처리. */
    public void onCreditTransfer(Pacs008 request) {
        ModeSettings settings = modeState.current();
        String endToEndId = request.endToEndId();

        if (settings.mode() == kr.paycore.simulator.mode.SimulatorMode.DROP_REQUEST) {
            // 아무 기록도 남기지 않는다 — 상대은행은 이 이체를 '받은 적이 없다'(ADR-0009).
            log.warn(
                    "DROP_REQUEST — 이체 지시 유실 endToEndId={} msgId={} 계좌={}",
                    endToEndId,
                    request.msgId(),
                    AccountMasker.mask(request.cdtTrfTxInf().cdtrAcct()));
            return;
        }

        TxSts decidedStatus =
                settings.mode() == kr.paycore.simulator.mode.SimulatorMode.REJECT ? TxSts.RJCT : TxSts.ACSC;
        StsRsn decidedReason =
                settings.mode() == kr.paycore.simulator.mode.SimulatorMode.REJECT ? settings.rejectReason() : null;

        ProcessedTransfer candidate = new ProcessedTransfer(
                endToEndId,
                request.msgId(),
                request.cdtTrfTxInf().pmtId().txId(),
                request.cdtTrfTxInf().dbtrAcct(),
                request.cdtTrfTxInf().cdtrAcct(),
                request.cdtTrfTxInf().cdtrAgt(),
                request.amount(),
                request.cdtTrfTxInf().intrBkSttlmAmt().ccy(),
                decidedStatus,
                decidedReason,
                clock.instant());

        Optional<ProcessedTransfer> alreadyProcessed = store.recordIfAbsent(candidate);
        if (alreadyProcessed.isPresent()) {
            log.warn(
                    "중복 이체 지시 거절(DUPL) endToEndId={} 새msgId={} 기존msgId={}",
                    endToEndId,
                    request.msgId(),
                    alreadyProcessed.get().msgId());
            sender.send(reply(
                    request.msgId(), ClearingMsgType.PACS_008, request, TxSts.RJCT, StsRsn.DUPL, "이미 처리된 endToEndId"));
            return;
        }

        Pacs002 response = reply(
                request.msgId(),
                ClearingMsgType.PACS_008,
                request,
                decidedStatus,
                decidedReason,
                decidedReason == null ? null : "청산망 거절");

        switch (settings.mode()) {
            case PROCESS_BUT_NO_RESPONSE ->
                log.warn("PROCESS_BUT_NO_RESPONSE — 처리는 했으나 응답을 보내지 않는다 endToEndId={}", endToEndId);
            case DELAY -> {
                log.warn("DELAY({}) — 지연 응답 예약 endToEndId={}", settings.delay(), endToEndId);
                scheduler.schedule(() -> sender.send(response), clock.instant().plus(settings.delay()));
            }
            case DUPLICATE_RESPONSE -> {
                log.warn("DUPLICATE_RESPONSE — 동일 pacs.002 를 2회 송신 endToEndId={}", endToEndId);
                sender.send(response);
                sender.send(response);
            }
            case OUT_OF_ORDER -> {
                log.warn("OUT_OF_ORDER — 응답 버퍼링 endToEndId={}", endToEndId);
                sender.sendOutOfOrder(response, settings.outOfOrderBatch());
                scheduler.schedule(sender::flushPending, clock.instant().plus(sender.maxHold()));
            }
            default -> sender.send(response);
        }
    }

    /**
     * 상태 조회 처리 (docs §7.3).
     *
     * <p>여기가 "timeout ≠ 실패"를 성립시키는 지점이다. 기록이 없으면 {@code NOOR} 로 <b>받은 적 없음</b>을
     * 명확히 답한다 — 그래야 상대가 FAILED 를 확정하고 재송신 여부를 정책적으로 판단할 수 있다.
     */
    public void onStatusRequest(Pacs028 inquiry) {
        String endToEndId = inquiry.endToEndId();
        Optional<ProcessedTransfer> found = store.find(endToEndId);

        if (found.isEmpty()) {
            log.info("상태조회 응답: 원거래 수신 이력 없음(NOOR) endToEndId={}", endToEndId);
            sender.send(new Pacs002(
                    new Pacs002.GrpHdr(ids.newClearingMsgId(), clock.instant()),
                    new Pacs002.TxInfAndSts(
                            inquiry.msgId(),
                            ClearingMsgType.PACS_028,
                            endToEndId,
                            inquiry.txInf().orgnlTxId(),
                            TxSts.RJCT,
                            StsRsn.NOOR,
                            "원거래 수신 이력 없음")));
            return;
        }

        ProcessedTransfer transfer = found.get();
        log.info("상태조회 응답: {} endToEndId={}", transfer.status(), endToEndId);
        sender.send(new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), clock.instant()),
                new Pacs002.TxInfAndSts(
                        inquiry.msgId(),
                        ClearingMsgType.PACS_028,
                        endToEndId,
                        transfer.txId(),
                        transfer.status(),
                        transfer.reason(),
                        "상태조회 응답")));
    }

    private Pacs002 reply(
            String orgnlMsgId, String orgnlMsgNmId, Pacs008 request, TxSts status, StsRsn reason, String addtlInf) {
        Instant now = clock.instant();
        return new Pacs002(
                new Pacs002.GrpHdr(ids.newClearingMsgId(), now),
                new Pacs002.TxInfAndSts(
                        orgnlMsgId,
                        orgnlMsgNmId,
                        request.endToEndId(),
                        request.cdtTrfTxInf().pmtId().txId(),
                        status,
                        reason,
                        addtlInf));
    }
}
