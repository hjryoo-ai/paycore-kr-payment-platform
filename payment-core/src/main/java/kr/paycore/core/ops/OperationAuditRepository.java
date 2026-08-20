package kr.paycore.core.ops;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationAuditRepository extends JpaRepository<OperationAudit, Long> {

    List<OperationAudit> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId);

    List<OperationAudit> findByOrderByCreatedAtDesc();
}
