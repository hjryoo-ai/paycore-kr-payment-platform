package kr.paycore.ledger.query;

import java.time.Instant;
import java.util.List;

/**
 * 분개 조회 응답.
 *
 * @param imbalance 차변 합 − 대변 합. 응답에 그대로 노출한다 — 장부가 맞는지를 숨기지 않는 것이 원칙이다
 */
public record JournalView(
        String journalId, String paymentId, Instant postedAt, List<EntryView> entries, long imbalance) {}
