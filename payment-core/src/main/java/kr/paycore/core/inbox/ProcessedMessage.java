package kr.paycore.core.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/**
 * 소비자 멱등성을 위한 inbox 레코드 (docs §6, §7.2 / CLAUDE.md 불변식 4).
 *
 * <p>PK 가 (consumerGroup, messageId) 인 것이 핵심이다. 같은 메시지를 서로 다른 소비자 그룹이
 * 각자 한 번씩 처리하는 것은 정상이고, <b>같은 그룹이 두 번</b> 처리하는 것만 막아야 한다.
 */
@Entity
@Table(name = "PROCESSED_MESSAGE")
@IdClass(ProcessedMessage.Key.class)
public class ProcessedMessage {

    @Id
    @Column(name = "CONSUMER_GROUP", length = 50, nullable = false, updatable = false)
    private String consumerGroup;

    @Id
    @Column(name = "MESSAGE_ID", length = 64, nullable = false, updatable = false)
    private String messageId;

    @Column(name = "PROCESSED_AT", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
        // JPA
    }

    public ProcessedMessage(String consumerGroup, String messageId, Instant processedAt) {
        this.consumerGroup = consumerGroup;
        this.messageId = messageId;
        this.processedAt = processedAt;
    }

    public String consumerGroup() {
        return consumerGroup;
    }

    public String messageId() {
        return messageId;
    }

    public Instant processedAt() {
        return processedAt;
    }

    /** 복합 PK. record 라 equals/hashCode 가 자동이며 JPA 요구사항을 만족한다. */
    public record Key(String consumerGroup, String messageId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
