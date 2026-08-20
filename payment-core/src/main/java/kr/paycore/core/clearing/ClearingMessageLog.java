package kr.paycore.core.clearing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.paycore.common.clearing.Direction;

/**
 * 주고받은 청산 메시지 원문 기록 (docs §6, §5.3).
 *
 * <p>존재 이유가 분명하다: <b>송신 전에</b> 이 행을 커밋해야 "보냈는지 모르는 상태"가 생기지 않는다.
 * 그리고 이 로그가 곧 inquiry 재시도 횟수의 근거이자 EOD 대사의 우리 쪽 증빙이다.
 */
@Entity
@Table(name = "CLEARING_MESSAGE_LOG")
public class ClearingMessageLog {

    @Id
    @Column(name = "MSG_ID", length = 36, nullable = false, updatable = false)
    private String msgId;

    @Column(name = "PAYMENT_ID", length = 26, nullable = false, updatable = false)
    private String paymentId;

    @Column(name = "END_TO_END_ID", length = 35, nullable = false, updatable = false)
    private String endToEndId;

    @Column(name = "MSG_TYPE", length = 10, nullable = false, updatable = false)
    private String msgType;

    @Enumerated(EnumType.STRING)
    @Column(name = "DIRECTION", length = 3, nullable = false, updatable = false)
    private Direction direction;

    @Lob
    @Column(name = "PAYLOAD", nullable = false, updatable = false)
    private String payload;

    @Column(name = "SENT_AT", nullable = false, updatable = false)
    private Instant sentAt;

    protected ClearingMessageLog() {
        // JPA
    }

    public ClearingMessageLog(
            String msgId,
            String paymentId,
            String endToEndId,
            String msgType,
            Direction direction,
            String payload,
            Instant sentAt) {
        this.msgId = msgId;
        this.paymentId = paymentId;
        this.endToEndId = endToEndId;
        this.msgType = msgType;
        this.direction = direction;
        this.payload = payload;
        this.sentAt = sentAt;
    }

    public String msgId() {
        return msgId;
    }

    public String paymentId() {
        return paymentId;
    }

    public String endToEndId() {
        return endToEndId;
    }

    public String msgType() {
        return msgType;
    }

    public Direction direction() {
        return direction;
    }

    public String payload() {
        return payload;
    }

    public Instant sentAt() {
        return sentAt;
    }
}
