package kr.paycore.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * MDC 누수 테스트.
 *
 * <p>스레드 풀에서 MDC 를 지우지 않으면 <b>다음 결제의 로그에 엉뚱한 endToEndId 가 붙는다</b>.
 * 사고 조사에서 이보다 나쁜 것은 없다 — 없는 로그보다 틀린 로그가 더 오래 사람을 붙든다.
 */
class PaymentMdcTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("스코프 안에서만 값이 보이고, 빠져나오면 흔적이 남지 않는다")
    void scopeCleansUp() {
        assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isNull();

        try (PaymentMdc.Scope scope = PaymentMdc.with("PID1", "PC-E2E-1")) {
            assertThat(MDC.get(PaymentMdc.PAYMENT_ID)).isEqualTo("PID1");
            assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isEqualTo("PC-E2E-1");
        }

        assertThat(MDC.get(PaymentMdc.PAYMENT_ID)).isNull();
        assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isNull();
    }

    @Test
    @DisplayName("중첩되면 안쪽이 우선하고, 닫히면 바깥 값으로 정확히 되돌아간다")
    void nestedScopesRestorePreviousValues() {
        try (PaymentMdc.Scope outer = PaymentMdc.with("PID1", "PC-E2E-1")) {
            try (PaymentMdc.Scope inner = PaymentMdc.with("PID2", "PC-E2E-2")) {
                assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isEqualTo("PC-E2E-2");
            }
            assertThat(MDC.get(PaymentMdc.END_TO_END_ID))
                    .as("안쪽 스코프가 바깥 값을 지워 버리면 안 된다")
                    .isEqualTo("PC-E2E-1");
        }
        assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isNull();
    }

    @Test
    @DisplayName("예외가 나도 MDC 는 정리된다")
    void cleansUpOnException() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> {
                    try (PaymentMdc.Scope scope = PaymentMdc.with("PID1", "PC-E2E-1")) {
                        throw new IllegalStateException("의도된 실패");
                    }
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isNull();
    }

    @Test
    @DisplayName("null 은 키를 넣지 않는다 — 'null' 문자열이 로그에 남으면 검색이 오염된다")
    void nullValuesRemoveKeys() {
        MDC.put(PaymentMdc.END_TO_END_ID, "이전값");

        try (PaymentMdc.Scope scope = PaymentMdc.with("PID1", null)) {
            assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isNull();
        }

        assertThat(MDC.get(PaymentMdc.END_TO_END_ID)).isEqualTo("이전값");
    }
}
