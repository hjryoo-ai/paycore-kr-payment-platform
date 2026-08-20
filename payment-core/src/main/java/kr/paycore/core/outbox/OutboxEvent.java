package kr.paycore.core.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Transactional Outbox 레코드 (docs §6, §7.1).
 *
 * <p>상태 변경과 이 행의 INSERT 가 같은 로컬 트랜잭션에 들어가는 것이 이 패턴의 전부다.
 * "커밋 후 Kafka 발행"은 발행 직전 크래시에서 이벤트를 잃고, "발행 후 커밋"은 롤백 시 유령 이벤트를 만든다.
 * dual-write 는 원자적일 수 없으므로 아예 하지 않는다.
 */
@Entity
@Table(name = "OUTBOX_EVENT")
public class OutboxEvent {

    @Id
    @Column(name = "EVENT_ID", length = 26, nullable = false, updatable = false)
    private String eventId;

    /** Kafka 파티션 키. paymentId 를 쓰므로 같은 결제의 이벤트는 순서가 보장된다(docs §7.4). */
    @Column(name = "AGGREGATE_ID", length = 26, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "EVENT_TYPE", length = 50, nullable = false, updatable = false)
    private String eventType;

    @Lob
    @Column(name = "PAYLOAD", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 10, nullable = false)
    private OutboxStatus status;

    @Column(name = "ATTEMPTS", nullable = false)
    private int attempts;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "PUBLISHED_AT")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String eventId, String aggregateId, String eventType, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.NEW;
        this.attempts = 0;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant at) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = at;
        this.attempts++;
    }

    public void markAttemptFailed() {
        this.attempts++;
    }

    public String eventId() {
        return eventId;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public OutboxStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }
}
