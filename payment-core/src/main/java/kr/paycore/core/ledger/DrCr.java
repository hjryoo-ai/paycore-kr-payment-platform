package kr.paycore.core.ledger;

/** 차변/대변. 값은 {@code LEDGER_ENTRY.DR_CR} 컬럼(CHAR(1))과 그대로 대응한다. */
public enum DrCr {
    /** 차변 Debit. */
    D,
    /** 대변 Credit. */
    C
}
