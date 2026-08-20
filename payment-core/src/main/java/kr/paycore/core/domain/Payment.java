package kr.paycore.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * 결제 애그리거트 루트 (docs §6 PAYMENT).
 *
 * <p>금액은 원화 정수라서 {@code long} 이다. 부동소수점 타입은 금지(CLAUDE.md).
 *
 * <p>상태는 이 클래스가 아니라 {@code PaymentStateMachine} 을 통해서만 바뀐다. 그래서 {@link #applyStatus}
 * 는 package-private 이 아니라 명시적으로 "상태머신이 호출한다"는 주석을 달아 공개해 두었다.
 */
@Entity
@Table(name = "PAYMENT")
public class Payment {

    @Id
    @Column(name = "PAYMENT_ID", length = 26, nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "IDEMPOTENCY_KEY", length = 64, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "END_TO_END_ID", length = 35, nullable = false, updatable = false)
    private String endToEndId;

    @Column(name = "DEBTOR_ACCOUNT", length = 32, nullable = false, updatable = false)
    private String debtorAccount;

    @Column(name = "CREDITOR_ACCOUNT", length = 32, nullable = false, updatable = false)
    private String creditorAccount;

    @Column(name = "CREDITOR_BANK", length = 3, nullable = false, updatable = false)
    private String creditorBank;

    @Column(name = "AMOUNT", nullable = false, updatable = false)
    private long amount;

    @Column(name = "CURRENCY", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "REMITTANCE_INFO", length = 140, updatable = false)
    private String remittanceInfo;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 20, nullable = false)
    private PaymentStatus status;

    @Version
    @Column(name = "VERSION", nullable = false)
    private long version;

    /** 접수 시점에 만들어 저장한 응답 본문. 재시도 시 '재실행'이 아니라 이 값을 그대로 돌려준다(docs §5.1). */
    @Lob
    @Column(name = "FIRST_RESPONSE")
    private String firstResponse;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    private Payment(Builder b) {
        this.paymentId = b.paymentId;
        this.idempotencyKey = b.idempotencyKey;
        this.endToEndId = b.endToEndId;
        this.debtorAccount = b.debtorAccount;
        this.creditorAccount = b.creditorAccount;
        this.creditorBank = b.creditorBank;
        this.amount = b.amount;
        this.currency = b.currency;
        this.remittanceInfo = b.remittanceInfo;
        this.status = b.status;
        this.firstResponse = b.firstResponse;
        this.createdAt = b.createdAt;
        this.updatedAt = b.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 상태머신 전용. 직접 호출하지 말 것 — 전이 규칙 검증을 건너뛰게 된다. */
    public void applyStatus(PaymentStatus next, Instant at) {
        this.status = Objects.requireNonNull(next, "next");
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    public String paymentId() {
        return paymentId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String endToEndId() {
        return endToEndId;
    }

    public String debtorAccount() {
        return debtorAccount;
    }

    public String creditorAccount() {
        return creditorAccount;
    }

    public String creditorBank() {
        return creditorBank;
    }

    public long amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public String remittanceInfo() {
        return remittanceInfo;
    }

    public PaymentStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public String firstResponse() {
        return firstResponse;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public static final class Builder {
        private String paymentId;
        private String idempotencyKey;
        private String endToEndId;
        private String debtorAccount;
        private String creditorAccount;
        private String creditorBank;
        private long amount;
        private String currency;
        private String remittanceInfo;
        private PaymentStatus status = PaymentStatus.RECEIVED;
        private String firstResponse;
        private Instant createdAt;

        public Builder paymentId(String v) {
            this.paymentId = v;
            return this;
        }

        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        public Builder endToEndId(String v) {
            this.endToEndId = v;
            return this;
        }

        public Builder debtorAccount(String v) {
            this.debtorAccount = v;
            return this;
        }

        public Builder creditorAccount(String v) {
            this.creditorAccount = v;
            return this;
        }

        public Builder creditorBank(String v) {
            this.creditorBank = v;
            return this;
        }

        public Builder amount(long v) {
            this.amount = v;
            return this;
        }

        public Builder currency(String v) {
            this.currency = v;
            return this;
        }

        public Builder remittanceInfo(String v) {
            this.remittanceInfo = v;
            return this;
        }

        public Builder status(PaymentStatus v) {
            this.status = v;
            return this;
        }

        public Builder firstResponse(String v) {
            this.firstResponse = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Payment build() {
            return new Payment(this);
        }
    }
}
