package kr.paycore.core.ops;

/** DLT 워크리스트 항목의 처리 상태. */
public enum DeadLetterStatus {
    /** 아직 사람이 보지 않았다. */
    NEW,
    /** 운영자가 원인을 확인하고 다시 발행했다. */
    REPUBLISHED,
    /** 운영자가 재처리하지 않기로 했다. */
    DISCARDED
}
