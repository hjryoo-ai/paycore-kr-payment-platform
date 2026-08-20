package kr.paycore.core.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 분개 명세 한 줄 (docs §6 LEDGER_ENTRY).
 *
 * <p>금액은 항상 <b>양수</b>이고 방향은 {@link DrCr} 이 나타낸다. 음수 금액으로 방향을 표현하면
 * 합계 0 검증이 부호 실수 하나로 조용히 통과해 버린다. DB 에도 {@code AMOUNT > 0} 제약이 있다.
 */
@Entity
@Table(name = "LEDGER_ENTRY")
public class LedgerEntry {

    @Id
    @Column(name = "ENTRY_ID", length = 26, nullable = false, updatable = false)
    private String entryId;

    @Column(name = "JOURNAL_ID", length = 26, nullable = false, updatable = false)
    private String journalId;

    @Column(name = "ACCOUNT_ID", length = 32, nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "DR_CR", length = 1, nullable = false, updatable = false)
    private DrCr drCr;

    @Column(name = "AMOUNT", nullable = false, updatable = false)
    private long amount;

    protected LedgerEntry() {
        // JPA
    }

    public LedgerEntry(String entryId, String journalId, String accountId, DrCr drCr, long amount) {
        this.entryId = entryId;
        this.journalId = journalId;
        this.accountId = accountId;
        this.drCr = drCr;
        this.amount = amount;
    }

    public String entryId() {
        return entryId;
    }

    public String journalId() {
        return journalId;
    }

    public String accountId() {
        return accountId;
    }

    public DrCr drCr() {
        return drCr;
    }

    public long amount() {
        return amount;
    }

    /** 합계 0 검증용 부호 적용 금액. 차변은 +, 대변은 −. */
    public long signed() {
        return drCr == DrCr.D ? amount : -amount;
    }
}
