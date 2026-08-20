package kr.paycore.core.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.domain.PaymentStatusHistory;
import kr.paycore.core.domain.PaymentStatusHistoryRepository;
import kr.paycore.core.observability.PaymentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 상태 전이표 <b>전수</b> 테스트 (docs §9 — 허용 전이와 금지 전이를 모두 덮는다).
 *
 * <p>전이표는 9개 상태 × 9개 목표 = 81개 조합이다. 몇 개만 골라 테스트하면, 나중에 표를 고칠 때
 * 실수로 열어 버린 전이를 아무도 못 잡는다. 그래서 조합을 코드로 생성해 전부 확인한다.
 */
class PaymentStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-08-19T02:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

    private PaymentStatusHistoryRepository histories;
    private PaymentMetrics metrics;
    private PaymentStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        histories = mock(PaymentStatusHistoryRepository.class);
        // 메트릭은 실제 레지스트리로 둔다 — 목으로 두면 '전이가 세어지는가'를 확인할 수 없다.
        metrics = new PaymentMetrics(new SimpleMeterRegistry());
        stateMachine = new PaymentStateMachine(histories, metrics, CLOCK);
    }

    /** docs §4.2 상태 다이어그램을 그대로 옮긴 기대 전이표. 구현과 독립적으로 여기 한 번 더 적는다. */
    private static Set<PaymentStatus> expectedTargets(PaymentStatus from) {
        return switch (from) {
            case RECEIVED -> EnumSet.of(PaymentStatus.VALIDATED, PaymentStatus.REJECTED);
            case VALIDATED -> EnumSet.of(PaymentStatus.SENT_TO_CLEARING, PaymentStatus.REJECTED);
            case SENT_TO_CLEARING -> EnumSet.of(PaymentStatus.CLEARED, PaymentStatus.FAILED, PaymentStatus.UNKNOWN);
            case UNKNOWN -> EnumSet.of(PaymentStatus.CLEARED, PaymentStatus.FAILED, PaymentStatus.MANUAL_REVIEW);
            case MANUAL_REVIEW -> EnumSet.of(PaymentStatus.CLEARED, PaymentStatus.FAILED);
            // 모순된 늦은 응답은 상태를 뒤집지 않고 사람에게 넘긴다 (docs §7.4)
            case CLEARED -> EnumSet.of(PaymentStatus.SETTLED, PaymentStatus.MANUAL_REVIEW);
            case REJECTED, FAILED, SETTLED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    static List<Arguments> allCombinations() {
        List<Arguments> all = new ArrayList<>();
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                all.add(Arguments.of(from, to));
            }
        }
        return all;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allCombinations")
    @DisplayName("81개 (from, to) 조합 전수: 표에 있는 전이만 성공하고 나머지는 예외")
    void exhaustiveTransitionTable(PaymentStatus from, PaymentStatus to) {
        Payment payment = paymentInStatus(from);

        if (from == to) {
            // 같은 상태로의 재적용은 멱등 no-op — at-least-once 메시징의 전제다.
            assertThat(stateMachine.transition(payment, to, "msg-1", null)).isFalse();
            assertThat(payment.status()).isEqualTo(from);
            verify(histories, never()).save(any());
            return;
        }

        if (expectedTargets(from).contains(to)) {
            assertThat(stateMachine.transition(payment, to, "msg-1", "사유")).isTrue();
            assertThat(payment.status()).isEqualTo(to);
            assertThat(payment.updatedAt()).isEqualTo(NOW);
            verify(histories, times(1)).save(any(PaymentStatusHistory.class));
        } else {
            assertThatThrownBy(() -> stateMachine.transition(payment, to, "msg-1", null))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
            assertThat(payment.status()).as("실패한 전이는 상태를 건드리지 않는다").isEqualTo(from);
            verify(histories, never()).save(any());
        }
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("종결 상태(REJECTED/FAILED/SETTLED)에서는 어떤 전이도 나갈 수 없다")
    void terminalStatesHaveNoOutgoingTransitions(PaymentStatus status) {
        if (!status.isTerminal()) {
            return;
        }
        assertThat(PaymentStateMachine.allowedFrom(status)).isEmpty();
    }

    @Test
    @DisplayName("CLEARED 에서 UNKNOWN 으로 역행할 수 없다 — 늦게 온 응답이 확정 상태를 덮으면 이중 지급의 시작이다")
    void clearedCannotGoBackToUnknown() {
        Payment payment = paymentInStatus(PaymentStatus.CLEARED);

        assertThatThrownBy(() -> stateMachine.transition(payment, PaymentStatus.UNKNOWN, "late-pacs002", null))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("전이 이력에는 from/to 와 무엇이 일으켰는지가 함께 남는다")
    void historyRecordsTrigger() {
        Payment payment = paymentInStatus(PaymentStatus.SENT_TO_CLEARING);
        var captor = org.mockito.ArgumentCaptor.forClass(PaymentStatusHistory.class);

        stateMachine.transition(payment, PaymentStatus.UNKNOWN, "pacs008-msg-42", "응답 timeout 10s");

        verify(histories).save(captor.capture());
        PaymentStatusHistory saved = captor.getValue();
        assertThat(saved.fromStatus()).isEqualTo(PaymentStatus.SENT_TO_CLEARING);
        assertThat(saved.toStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(saved.triggeredBy()).isEqualTo("pacs008-msg-42");
        assertThat(saved.reason()).isEqualTo("응답 timeout 10s");
        assertThat(saved.createdAt()).isEqualTo(NOW);
    }

    private static Payment paymentInStatus(PaymentStatus status) {
        return Payment.builder()
                .paymentId("01ABCDEFGHJKMNPQRSTVWXYZ00")
                .idempotencyKey("idem-1")
                .endToEndId("PC01ABCDEFGHJKMNPQRSTVWXYZ00")
                .debtorAccount("110-123-456789")
                .creditorAccount("352-987-654321")
                .creditorBank("088")
                .amount(1_500_000L)
                .currency("KRW")
                .status(status)
                .createdAt(NOW.minusSeconds(60))
                .build();
    }
}
