package kr.paycore.gateway.inquiry;

import java.util.Optional;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.observability.PaymentMdc;
import kr.paycore.gateway.dispatch.ClearingSender;
import kr.paycore.gateway.dispatch.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * timeout 감지와 inquiry 스케줄을 돌리는 시계 (docs §5.3).
 *
 * <p>트랜잭션 경계는 {@link ClearingTimeoutService} / {@link InquiryService} 에 있고 여기엔 없다.
 * 같은 빈 안에서 {@code @Transactional} 메서드를 호출하면 프록시를 타지 않아 트랜잭션이 없는 채로
 * 도는데, 그 사실이 조용해서 더 위험하다.
 *
 * <p>테스트에서는 {@code paycore.gateway.watchdog-enabled=false} 로 끄고 서비스를 직접 호출해
 * 시간에 의존하지 않는 검증을 한다.
 */
@Component
@ConditionalOnProperty(
        prefix = "paycore.gateway",
        name = "watchdog-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ClearingWatchdog {

    private static final Logger log = LoggerFactory.getLogger(ClearingWatchdog.class);

    private final ClearingTimeoutService timeouts;
    private final InquiryService inquiries;
    private final ClearingSender sender;

    public ClearingWatchdog(ClearingTimeoutService timeouts, InquiryService inquiries, ClearingSender sender) {
        this.timeouts = timeouts;
        this.inquiries = inquiries;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${paycore.gateway.timeout-scan-interval:5s}")
    public void detectTimeouts() {
        try {
            markTimedOut();
        } catch (RuntimeException e) {
            log.error("timeout 감지 주기 실패 — 다음 주기에 재시도한다", e);
        }
    }

    @Scheduled(fixedDelayString = "${paycore.gateway.inquiry-scan-interval:5s}")
    public void runInquiries() {
        try {
            processUnknown();
        } catch (RuntimeException e) {
            log.error("inquiry 주기 실패 — 다음 주기에 재시도한다", e);
        }
    }

    /** 테스트에서 직접 호출한다. @return UNKNOWN 으로 옮긴 건수 */
    public int markTimedOut() {
        int marked = 0;
        for (Payment payment : timeouts.findTimedOut()) {
            try (PaymentMdc.Scope scope = PaymentMdc.with(payment.paymentId(), payment.endToEndId())) {
                if (timeouts.markUnknown(payment.paymentId())) {
                    marked++;
                }
            }
        }
        return marked;
    }

    /** 테스트에서 직접 호출한다. @return 조회 송신 + 에스컬레이션 건수 */
    public int processUnknown() {
        int acted = 0;
        for (Payment payment : inquiries.findUnknown()) {
            // 한 건이 실패해도 나머지는 계속 본다. 조회를 못 보낸 결제 하나가 전체 주기를 멈추면
            // 그 뒤의 UNKNOWN 들은 아무도 확인해 주지 않는다.
            try (PaymentMdc.Scope scope = PaymentMdc.with(payment.paymentId(), payment.endToEndId())) {
                acted += actOn(payment);
            } catch (RuntimeException e) {
                log.error("조회 처리 실패 paymentId={} — 다음 주기에 재시도한다", payment.paymentId(), e);
            }
        }
        return acted;
    }

    private int actOn(Payment payment) {
        switch (inquiries.decide(payment)) {
            case SEND -> {
                Optional<OutgoingMessage> prepared = inquiries.prepareInquiry(payment.paymentId());
                if (prepared.isEmpty()) {
                    return 0;
                }
                try {
                    sender.send(prepared.get());
                } catch (RuntimeException e) {
                    // 기록은 됐는데 나가지 못했다. 시도 횟수만 소진되면 조회를 한 번도 못 한 채
                    // MANUAL_REVIEW 로 밀려나므로, 기록을 되돌려 다음 주기에 다시 시도하게 한다.
                    inquiries.undoInquiry(prepared.get().msgId());
                    throw e;
                }
                return 1;
            }
            case ESCALATE -> {
                return inquiries.escalateToManualReview(payment.paymentId()) ? 1 : 0;
            }
            default -> {
                // backoff 대기 중.
                return 0;
            }
        }
    }
}
