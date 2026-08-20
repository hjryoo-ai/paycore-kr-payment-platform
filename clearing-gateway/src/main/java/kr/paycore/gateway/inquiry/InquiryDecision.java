package kr.paycore.gateway.inquiry;

/** UNKNOWN 건에 대해 지금 무엇을 할지. */
public enum InquiryDecision {
    /** 아직 backoff 대기 중. */
    WAIT,
    /** pacs.028 을 보낼 차례. */
    SEND,
    /** 정해진 횟수를 다 썼는데도 답이 없다 — 사람에게 넘긴다. */
    ESCALATE
}
