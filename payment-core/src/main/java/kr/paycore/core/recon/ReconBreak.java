package kr.paycore.core.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 일마감 대사에서 발견한 불일치 한 건 (docs §6 RECON_BREAK, §5.6).
 *
 * <p>{@code PAYMENT_ID} 가 null 일 수 있다 — 청산망 파일에는 있는데 우리 DB 에는 아예 없는 건이 그렇다.
 * 그런 건이야말로 반드시 남겨야 하므로 FK 를 걸지 않았다.
 */
@Entity
@Table(name = "RECON_BREAK")
public class ReconBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BREAK_ID", nullable = false, updatable = false)
    private Long breakId;

    @Column(name = "RECON_DATE", nullable = false, updatable = false)
    private LocalDate reconDate;

    @Column(name = "PAYMENT_ID", length = 26, updatable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "BREAK_TYPE", length = 30, nullable = false, updatable = false)
    private BreakType breakType;

    @Column(name = "DETAIL", length = 1000, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 10, nullable = false)
    private BreakStatus status;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconBreak() {
        // JPA
    }

    public ReconBreak(LocalDate reconDate, String paymentId, BreakType breakType, String detail, Instant createdAt) {
        this.reconDate = reconDate;
        this.paymentId = paymentId;
        this.breakType = breakType;
        this.detail = detail;
        this.status = BreakStatus.OPEN;
        this.createdAt = createdAt;
    }

    public void resolve() {
        this.status = BreakStatus.RESOLVED;
    }

    public Long breakId() {
        return breakId;
    }

    public LocalDate reconDate() {
        return reconDate;
    }

    public String paymentId() {
        return paymentId;
    }

    public BreakType breakType() {
        return breakType;
    }

    public String detail() {
        return detail;
    }

    public BreakStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
