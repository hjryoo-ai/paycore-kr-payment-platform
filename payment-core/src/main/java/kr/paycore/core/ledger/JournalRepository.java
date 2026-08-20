package kr.paycore.core.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalRepository extends JpaRepository<Journal, String> {

    Optional<Journal> findByPaymentId(String paymentId);

    boolean existsByPaymentId(String paymentId);

    List<Journal> findByPostedAtGreaterThanEqualAndPostedAtLessThan(Instant fromInclusive, Instant toExclusive);
}
