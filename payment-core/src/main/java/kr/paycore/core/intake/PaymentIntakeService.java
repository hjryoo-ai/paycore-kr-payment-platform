package kr.paycore.core.intake;

import java.time.Instant;
import java.util.Optional;
import kr.paycore.common.id.Ids;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 결제 접수 + 멱등성 (docs §5.1).
 *
 * <pre>
 * Idempotency-Key 로 조회
 *   ├─ 있음 → 본문이 같은가?
 *   │           ├─ 같음  → 저장된 FIRST_RESPONSE 를 '그대로' 반환 (재실행 없음)
 *   │           └─ 다름  → IdempotencyKeyReusedException (클라이언트 버그)
 *   └─ 없음 → INSERT 시도
 *               ├─ 성공 → 신규 접수
 *               └─ UNIQUE 위반 → 동시 요청이 이겼다 → 재조회 후 위와 동일하게 재생
 * </pre>
 *
 * <p>이 클래스에는 {@code @Transactional} 이 없다. 트랜잭션 경계는 {@link PaymentIntakeStore} 가 가진다.
 */
@Service
public class PaymentIntakeService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntakeService.class);

    private final PaymentIntakeStore store;
    private final Ids ids;
    private final ObjectMapper objectMapper;

    public PaymentIntakeService(PaymentIntakeStore store, Ids ids, ObjectMapper objectMapper) {
        this.store = store;
        this.ids = ids;
        this.objectMapper = objectMapper;
    }

    public IntakeOutcome intake(String idempotencyKey, IntakeCommand command) {
        Optional<Payment> existing = store.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(idempotencyKey, command, existing.get());
        }

        Instant now = ids.now();
        String paymentId = ids.newPaymentId();
        String endToEndId = ids.newEndToEndId();
        String firstResponse =
                serialize(new PaymentAcceptedSnapshot(paymentId, endToEndId, PaymentStatus.RECEIVED.name(), now));

        Payment payment = Payment.builder()
                .paymentId(paymentId)
                .idempotencyKey(idempotencyKey)
                .endToEndId(endToEndId)
                .debtorAccount(command.debtorAccount())
                .creditorAccount(command.creditorAccount())
                .creditorBank(command.creditorBankCode())
                .amount(command.amount())
                .currency(command.currency())
                .remittanceInfo(command.remittanceInfo())
                .status(PaymentStatus.RECEIVED)
                .firstResponse(firstResponse)
                .createdAt(now)
                .build();

        try {
            Payment saved = store.insert(payment, "channel-api");
            log.info(
                    "결제 접수 paymentId={} endToEndId={} debtor={} creditor={} bank={} amount={}",
                    saved.paymentId(),
                    saved.endToEndId(),
                    AccountMasker.mask(saved.debtorAccount()),
                    AccountMasker.mask(saved.creditorAccount()),
                    saved.creditorBank(),
                    saved.amount());
            return IntakeOutcome.created(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시에 들어온 같은 키의 요청이 먼저 커밋했다. UNIQUE 제약이 잡아준 정상 경로다.
            Payment winner = store.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            log.info("멱등 경합 감지 — 기존 응답 재생 idempotencyKey={} paymentId={}", idempotencyKey, winner.paymentId());
            return replay(idempotencyKey, command, winner);
        }
    }

    private IntakeOutcome replay(String idempotencyKey, IntakeCommand command, Payment existing) {
        boolean sameRequest = command.matches(
                existing.debtorAccount(),
                existing.creditorAccount(),
                existing.creditorBank(),
                existing.amount(),
                existing.currency(),
                existing.remittanceInfo());
        if (!sameRequest) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        return IntakeOutcome.replayed(existing);
    }

    private String serialize(PaymentAcceptedSnapshot snapshot) {
        return objectMapper.writeValueAsString(snapshot);
    }
}
