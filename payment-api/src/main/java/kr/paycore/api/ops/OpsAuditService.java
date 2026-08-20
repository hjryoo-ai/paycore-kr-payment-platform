package kr.paycore.api.ops;

import java.time.Clock;
import kr.paycore.core.ops.OperationAudit;
import kr.paycore.core.ops.OperationAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 운영 개입 감사 기록 (docs §7.5 — "감사 로그 필수").
 *
 * <p>호출자의 트랜잭션 안에서 실행된다. 개입과 그 기록이 같은 커밋에 묶여야 "바꿨는데 기록이 없다"
 * 또는 그 반대가 생기지 않는다 — 아웃박스·inbox 와 같은 이유다.
 */
@Service
public class OpsAuditService {

    public static final String TARGET_PAYMENT = "PAYMENT";
    public static final String TARGET_DEAD_LETTER = "DEAD_LETTER";

    private static final Logger log = LoggerFactory.getLogger(OpsAuditService.class);

    private final OperationAuditRepository audits;
    private final Clock clock;

    public OpsAuditService(OperationAuditRepository audits, Clock clock) {
        this.audits = audits;
        this.clock = clock;
    }

    public void record(String actor, String action, String targetType, String targetId, String detail) {
        audits.save(new OperationAudit(actor, action, targetType, targetId, detail, clock.instant()));
        log.warn("운영 개입 actor={} action={} target={}:{} 사유={}", actor, action, targetType, targetId, detail);
    }
}
