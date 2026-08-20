package kr.paycore.core.clearing;

import java.util.List;
import java.util.Optional;
import kr.paycore.common.clearing.Direction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClearingMessageLogRepository extends JpaRepository<ClearingMessageLog, String> {

    /**
     * inquiry 재시도 횟수는 별도 컬럼이 아니라 이 개수로 센다 — 상태를 두 곳에 두면 반드시 어긋난다.
     */
    long countByPaymentIdAndMsgTypeAndDirection(String paymentId, String msgType, Direction direction);

    Optional<ClearingMessageLog> findTopByPaymentIdAndMsgTypeAndDirectionOrderBySentAtDesc(
            String paymentId, String msgType, Direction direction);

    List<ClearingMessageLog> findByPaymentIdOrderBySentAtAsc(String paymentId);

    List<ClearingMessageLog> findByEndToEndIdOrderBySentAtAsc(String endToEndId);
}
