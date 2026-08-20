package kr.paycore.api.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import kr.paycore.api.error.PaymentNotFoundException;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.intake.IntakeCommand;
import kr.paycore.core.intake.IntakeOutcome;
import kr.paycore.core.intake.PaymentIntakeService;
import kr.paycore.core.query.PaymentQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채널 API (docs §5.1). <b>비즈니스 판단은 하지 않는다</b> — 접수·검증·조회만 한다.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_SEARCH_DAYS = 7;

    private final PaymentIntakeService intake;
    private final PaymentQueryService query;
    private final Clock clock;

    public PaymentController(PaymentIntakeService intake, PaymentQueryService query, Clock clock) {
        this.intake = intake;
        this.query = query;
        this.clock = clock;
    }

    /**
     * 이체 접수. 재시도 시에도 <b>저장된 최초 응답 문자열을 그대로</b> 돌려주므로 본문이 바이트 단위로 동일하다.
     * 그래서 반환 타입이 DTO 가 아니라 String 이다 — 다시 직렬화하면 "재실행"의 여지가 생긴다.
     */
    @PostMapping
    public ResponseEntity<String> accept(
            @RequestHeader(name = "Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody PaymentIntakeRequest request) {

        IntakeOutcome outcome = intake.intake(
                idempotencyKey,
                new IntakeCommand(
                        request.debtorAccount(),
                        request.creditorAccount(),
                        request.creditorBankCode(),
                        request.amount(),
                        request.currency(),
                        request.remittanceInfo()));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .location(URI.create("/api/v1/payments/" + outcome.payment().paymentId()))
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.responseBody());
    }

    @GetMapping("/{paymentId}")
    public PaymentDetailResponse get(@PathVariable @Size(max = 26) String paymentId) {
        return query.findById(paymentId)
                .map(p -> PaymentDetailResponse.of(p, query.historyOf(p.paymentId())))
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /** 운영 조회. 기간 미지정 시 최근 7일. 기간은 [from, to) 반열림 구간이다. */
    @GetMapping
    public PageResponse<PaymentSummaryResponse> search(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_SEARCH_DAYS, ChronoUnit.DAYS);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return PageResponse.of(query.search(status, start, end, pageable), PaymentSummaryResponse::of);
    }
}
