package kr.paycore.core.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 분개 헤더 (docs §6 JOURNAL, §5.5).
 *
 * <p>{@code PAYMENT_ID} 에 UNIQUE 가 걸려 있는 것이 원장 멱등성의 최종 방어선이다. 같은 결제를 두 번
 * 소비해도 분개는 한 벌만 존재한다 — 코드가 실수해도 DB 가 막는다(docs §7.2).
 */
@Entity
@Table(name = "JOURNAL")
public class Journal {

    @Id
    @Column(name = "JOURNAL_ID", length = 26, nullable = false, updatable = false)
    private String journalId;

    @Column(name = "PAYMENT_ID", length = 26, nullable = false, updatable = false, unique = true)
    private String paymentId;

    @Column(name = "POSTED_AT", nullable = false, updatable = false)
    private Instant postedAt;

    protected Journal() {
        // JPA
    }

    public Journal(String journalId, String paymentId, Instant postedAt) {
        this.journalId = journalId;
        this.paymentId = paymentId;
        this.postedAt = postedAt;
    }

    public String journalId() {
        return journalId;
    }

    public String paymentId() {
        return paymentId;
    }

    public Instant postedAt() {
        return postedAt;
    }
}
