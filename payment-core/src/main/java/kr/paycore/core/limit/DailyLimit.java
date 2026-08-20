package kr.paycore.core.limit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** 계좌별 일일 이체 한도 (docs §5.2). 키는 (계좌, 업무일자). */
@Entity
@Table(name = "DAILY_LIMIT")
@IdClass(DailyLimit.Key.class)
public class DailyLimit {

    @Id
    @Column(name = "ACCOUNT_ID", length = 32, nullable = false, updatable = false)
    private String accountId;

    @Id
    @Column(name = "LIMIT_DATE", nullable = false, updatable = false)
    private LocalDate limitDate;

    @Column(name = "LIMIT_AMOUNT", nullable = false)
    private long limitAmount;

    @Column(name = "USED_AMOUNT", nullable = false)
    private long usedAmount;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected DailyLimit() {
        // JPA
    }

    public DailyLimit(String accountId, LocalDate limitDate, long limitAmount, Instant updatedAt) {
        this.accountId = accountId;
        this.limitDate = limitDate;
        this.limitAmount = limitAmount;
        this.usedAmount = 0L;
        this.updatedAt = updatedAt;
    }

    public boolean canConsume(long amount) {
        return usedAmount + amount <= limitAmount;
    }

    public void consume(long amount, Instant at) {
        if (!canConsume(amount)) {
            throw new IllegalStateException(
                    "일일 한도 초과: account=%s used=%d + %d > %d".formatted(accountId, usedAmount, amount, limitAmount));
        }
        this.usedAmount += amount;
        this.updatedAt = at;
    }

    public String accountId() {
        return accountId;
    }

    public LocalDate limitDate() {
        return limitDate;
    }

    public long limitAmount() {
        return limitAmount;
    }

    public long usedAmount() {
        return usedAmount;
    }

    public long remaining() {
        return limitAmount - usedAmount;
    }

    /** 복합 키. */
    public record Key(String accountId, LocalDate limitDate) implements Serializable {
        public Key {
            Objects.requireNonNull(accountId);
            Objects.requireNonNull(limitDate);
        }

        public Key() {
            this("", LocalDate.EPOCH);
        }
    }
}
