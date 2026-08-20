package kr.paycore.core.outbox;

/** 아웃박스 이벤트 상태. NEW 만 발행 대상이다. */
public enum OutboxStatus {
    NEW,
    PUBLISHED
}
