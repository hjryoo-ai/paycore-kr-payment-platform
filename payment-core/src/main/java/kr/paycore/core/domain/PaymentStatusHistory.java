package kr.paycore.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 상태 전이 감사 로그 (docs §6 PAYMENT_STATUS_HISTORY, §4.2).
 *
 * <p>모든 전이는 "무엇이 이 전이를 일으켰는가"({@code triggeredBy} = 메시지ID / 운영자ID)와 함께 기록된다.
 * 장애 분석에서 상태만으로는 답이 안 나오고, 어떤 메시지가 언제 상태를 바꿨는지가 필요하기 때문이다.
 */
@Entity
@Table(name = "PAYMENT_STATUS_HISTORY")
public class PaymentStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "PAYMENT_ID", length = 26, nullable = false, updatable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "FROM_STATUS", length = 20, updatable = false)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "TO_STATUS", length = 20, nullable = false, updatable = false)
    private PaymentStatus toStatus;

    @Column(name = "TRIGGERED_BY", length = 100, nullable = false, updatable = false)
    private String triggeredBy;

    @Column(name = "REASON", length = 400, updatable = false)
    private String reason;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {
        // JPA
    }

    public PaymentStatusHistory(
            String paymentId,
            PaymentStatus fromStatus,
            PaymentStatus toStatus,
            String triggeredBy,
            String reason,
            Instant createdAt) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.triggeredBy = triggeredBy;
        this.reason = truncate(reason);
        this.createdAt = createdAt;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 400 ? reason : reason.substring(0, 400);
    }

    public Long id() {
        return id;
    }

    public String paymentId() {
        return paymentId;
    }

    public PaymentStatus fromStatus() {
        return fromStatus;
    }

    public PaymentStatus toStatus() {
        return toStatus;
    }

    public String triggeredBy() {
        return triggeredBy;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
