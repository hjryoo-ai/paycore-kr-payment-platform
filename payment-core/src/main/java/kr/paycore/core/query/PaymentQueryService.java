package kr.paycore.core.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentRepository;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조회 전용 서비스 (docs §5.1 조회 API). 쓰기 경로와 섞이지 않도록 분리했다. */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository histories;

    public PaymentQueryService(PaymentRepository payments, PaymentStatusHistoryRepository histories) {
        this.payments = payments;
        this.histories = histories;
    }

    public Optional<Payment> findById(String paymentId) {
        return payments.findById(paymentId);
    }

    public Optional<Payment> findByEndToEndId(String endToEndId) {
        return payments.findByEndToEndId(endToEndId);
    }

    public List<PaymentStatusHistory> historyOf(String paymentId) {
        return histories.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId);
    }

    /** 기간은 [from, to) 반열림 구간. status 가 null 이면 전체 상태를 대상으로 한다. */
    public Page<Payment> search(PaymentStatus status, Instant from, Instant to, Pageable pageable) {
        return status == null
                ? payments.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to, pageable)
                : payments.findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(status, from, to, pageable);
    }
}
