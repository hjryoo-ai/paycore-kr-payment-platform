package kr.paycore.core.statemachine;

import static kr.paycore.core.domain.PaymentStatus.CLEARED;
import static kr.paycore.core.domain.PaymentStatus.FAILED;
import static kr.paycore.core.domain.PaymentStatus.MANUAL_REVIEW;
import static kr.paycore.core.domain.PaymentStatus.RECEIVED;
import static kr.paycore.core.domain.PaymentStatus.REJECTED;
import static kr.paycore.core.domain.PaymentStatus.SENT_TO_CLEARING;
import static kr.paycore.core.domain.PaymentStatus.SETTLED;
import static kr.paycore.core.domain.PaymentStatus.UNKNOWN;
import static kr.paycore.core.domain.PaymentStatus.VALIDATED;

import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 결제 상태 전이의 <b>유일한</b> 소유자 (docs §4.2).
 *
 * <p>규칙 세 가지:
 *
 * <ol>
 *   <li>전이는 아래 표에 있는 것만 가능하다. 표에 없으면 예외 + 알림.
 *   <li>모든 전이는 {@code PAYMENT_STATUS_HISTORY} 에 "무엇이 일으켰는가"와 함께 기록된다.
 *   <li>같은 상태로의 재전이(A -> A)는 <b>예외가 아니라 no-op</b> 이다. at-least-once 메시징에서 같은
 *       메시지를 두 번 받는 일은 정상이고, 그때 상태머신 자체가 멱등 장치가 되어야 한다(docs §7.2).
 * </ol>
 *
 * <p>역행 금지: {@code CLEARED} 이후에는 {@code UNKNOWN} 으로 돌아갈 수 없다. 늦게 도착한 pacs.002 가
 * 확정된 상태를 덮어쓰면 그게 곧 이중 지급의 시작이다. 그런 모순은 상태를 뒤집는 대신
 * {@code CLEARED -> MANUAL_REVIEW} 로 사람에게 넘긴다(docs §7.4) — 판단을 미루는 것이지 되돌리는 것이 아니다.
 */
@Component
public class PaymentStateMachine {

    private static final Logger log = LoggerFactory.getLogger(PaymentStateMachine.class);

    /** 허용 전이표. 이 표가 곧 docs §4.2 의 상태 다이어그램이다. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = buildTable();

    private final PaymentStatusHistoryRepository histories;
    private final Clock clock;

    public PaymentStateMachine(PaymentStatusHistoryRepository histories, Clock clock) {
        this.histories = histories;
        this.clock = clock;
    }

    private static Map<PaymentStatus, Set<PaymentStatus>> buildTable() {
        Map<PaymentStatus, Set<PaymentStatus>> table = new EnumMap<>(PaymentStatus.class);
        table.put(RECEIVED, EnumSet.of(VALIDATED, REJECTED));
        table.put(VALIDATED, EnumSet.of(SENT_TO_CLEARING, REJECTED));
        table.put(SENT_TO_CLEARING, EnumSet.of(CLEARED, FAILED, UNKNOWN));
        table.put(UNKNOWN, EnumSet.of(CLEARED, FAILED, MANUAL_REVIEW));
        table.put(MANUAL_REVIEW, EnumSet.of(CLEARED, FAILED));
        // CLEARED 에서 나가는 길은 둘뿐이다: 정상 마감(SETTLED), 그리고 사람의 확인(MANUAL_REVIEW).
        // 후자는 늦게 도착한 모순된 응답 때문이다 — 자동으로 FAILED 로 덮어쓰지 않고 운영자에게 넘긴다(docs §7.4).
        table.put(CLEARED, EnumSet.of(SETTLED, MANUAL_REVIEW));
        table.put(REJECTED, EnumSet.noneOf(PaymentStatus.class));
        table.put(FAILED, EnumSet.noneOf(PaymentStatus.class));
        table.put(SETTLED, EnumSet.noneOf(PaymentStatus.class));
        return Map.copyOf(table);
    }

    /** 전이표 조회 — 테스트와 문서 생성이 같은 진실을 보게 하기 위해 공개한다. */
    public static Set<PaymentStatus> allowedFrom(PaymentStatus from) {
        return ALLOWED.get(from);
    }

    public static boolean isAllowed(PaymentStatus from, PaymentStatus to) {
        return from == to || ALLOWED.get(from).contains(to);
    }

    /**
     * 상태를 전이시키고 이력을 남긴다. 호출자의 트랜잭션 안에서 실행된다.
     *
     * @param triggeredBy 이 전이를 일으킨 주체 — 메시지ID 또는 운영자ID. 빈 값 금지.
     * @return 실제로 상태가 바뀌었으면 true, 멱등 재적용(no-op)이면 false
     */
    public boolean transition(Payment payment, PaymentStatus to, String triggeredBy, String reason) {
        PaymentStatus from = payment.status();

        if (from == to) {
            log.debug("멱등 재적용 — 전이 없음 paymentId={} status={} triggeredBy={}", payment.paymentId(), to, triggeredBy);
            return false;
        }
        if (!ALLOWED.get(from).contains(to)) {
            throw new IllegalStateTransitionException(payment.paymentId(), from, to);
        }

        payment.applyStatus(to, clock.instant());
        histories.save(new PaymentStatusHistory(payment.paymentId(), from, to, triggeredBy, reason, clock.instant()));
        log.info(
                "상태 전이 paymentId={} {} -> {} triggeredBy={} reason={}",
                payment.paymentId(),
                from,
                to,
                triggeredBy,
                reason);
        return true;
    }
}
