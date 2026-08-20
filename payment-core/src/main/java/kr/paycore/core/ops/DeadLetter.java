package kr.paycore.core.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * DLT 로 밀려난 메시지 (docs §7.5, §5.7 워크리스트).
 *
 * <p>토픽에만 두면 운영자가 볼 수 없고 재발행 이력도 남길 곳이 없다. 그래서 DLT 를 소비해
 * 여기에 적재한다. <b>자동 재주입은 하지 않는다</b> — 왜 실패했는지 모른 채 다시 넣는 것은
 * 같은 실패를 반복하거나, 더 나쁘게는 이미 처리된 일을 두 번 하게 만든다.
 */
@Entity
@Table(name = "DEAD_LETTER")
public class DeadLetter {

    @Id
    @Column(name = "DEAD_LETTER_ID", length = 26, nullable = false, updatable = false)
    private String deadLetterId;

    @Column(name = "ORIGINAL_TOPIC", length = 100, nullable = false, updatable = false)
    private String originalTopic;

    @Column(name = "ORIGINAL_PARTITION", updatable = false)
    private Integer originalPartition;

    @Column(name = "ORIGINAL_OFFSET", updatable = false)
    private Long originalOffset;

    @Column(name = "MESSAGE_KEY", length = 64, updatable = false)
    private String messageKey;

    @Column(name = "EVENT_ID", length = 26, updatable = false)
    private String eventId;

    @Column(name = "EVENT_TYPE", length = 50, updatable = false)
    private String eventType;

    @Lob
    @Column(name = "PAYLOAD", nullable = false, updatable = false)
    private String payload;

    @Column(name = "EXCEPTION_TYPE", length = 200, updatable = false)
    private String exceptionType;

    @Column(name = "EXCEPTION_MESSAGE", length = 1000, updatable = false)
    private String exceptionMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 12, nullable = false)
    private DeadLetterStatus status;

    @Column(name = "RECEIVED_AT", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "RESOLVED_AT")
    private Instant resolvedAt;

    protected DeadLetter() {
        // JPA
    }

    public DeadLetter(
            String deadLetterId,
            String originalTopic,
            Integer originalPartition,
            Long originalOffset,
            String messageKey,
            String eventId,
            String eventType,
            String payload,
            String exceptionType,
            String exceptionMessage,
            Instant receivedAt) {
        this.deadLetterId = deadLetterId;
        this.originalTopic = originalTopic;
        this.originalPartition = originalPartition;
        this.originalOffset = originalOffset;
        this.messageKey = messageKey;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.exceptionType = exceptionType;
        this.exceptionMessage = exceptionMessage;
        this.status = DeadLetterStatus.NEW;
        this.receivedAt = receivedAt;
    }

    /** 운영자 확인 후 재발행. 상태를 되돌리지는 않는다 — 재발행 이력 자체가 감사 대상이다. */
    public void markRepublished(Instant at) {
        this.status = DeadLetterStatus.REPUBLISHED;
        this.resolvedAt = at;
    }

    public void markDiscarded(Instant at) {
        this.status = DeadLetterStatus.DISCARDED;
        this.resolvedAt = at;
    }

    public boolean isOpen() {
        return status == DeadLetterStatus.NEW;
    }

    public String deadLetterId() {
        return deadLetterId;
    }

    public String originalTopic() {
        return originalTopic;
    }

    public Integer originalPartition() {
        return originalPartition;
    }

    public Long originalOffset() {
        return originalOffset;
    }

    public String messageKey() {
        return messageKey;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public String exceptionType() {
        return exceptionType;
    }

    public String exceptionMessage() {
        return exceptionMessage;
    }

    public DeadLetterStatus status() {
        return status;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }
}
