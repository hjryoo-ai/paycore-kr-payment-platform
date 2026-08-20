package kr.paycore.core.ops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 운영자가 시스템 상태에 개입한 기록 (docs §7.5 — "감사 로그 필수").
 *
 * <p>자동화가 손을 뗀 지점에서만 사람이 개입한다. 그래서 이 테이블에 남는 모든 줄은
 * "시스템이 스스로 결론 내지 못한 건"이고, 사후에 가장 먼저 조사받는 대상이다.
 * 수정도 삭제도 하지 않는다 — append-only 다.
 */
@Entity
@Table(name = "OPERATION_AUDIT")
public class OperationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "AUDIT_ID", nullable = false, updatable = false)
    private Long auditId;

    @Column(name = "ACTOR", length = 64, nullable = false, updatable = false)
    private String actor;

    @Column(name = "ACTION", length = 40, nullable = false, updatable = false)
    private String action;

    @Column(name = "TARGET_TYPE", length = 20, nullable = false, updatable = false)
    private String targetType;

    @Column(name = "TARGET_ID", length = 64, nullable = false, updatable = false)
    private String targetId;

    @Column(name = "DETAIL", length = 1000, updatable = false)
    private String detail;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    protected OperationAudit() {
        // JPA
    }

    public OperationAudit(
            String actor, String action, String targetType, String targetId, String detail, Instant createdAt) {
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long auditId() {
        return auditId;
    }

    public String actor() {
        return actor;
    }

    public String action() {
        return action;
    }

    public String targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public String detail() {
        return detail;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
