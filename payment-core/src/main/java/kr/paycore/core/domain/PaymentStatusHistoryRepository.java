package kr.paycore.core.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {

    List<PaymentStatusHistory> findByPaymentIdOrderByCreatedAtAscIdAsc(String paymentId);
}
