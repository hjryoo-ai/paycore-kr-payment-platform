package kr.paycore.api.error;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import kr.paycore.common.error.ErrorCode;
import kr.paycore.core.intake.IdempotencyKeyReusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 problem+json 오류 응답 (docs §10.2).
 *
 * <p>원칙: 스택트레이스·SQL·내부 클래스명은 <b>응답에 절대 넣지 않는다</b>. 상세 원인은 로그에만 남기고,
 * 클라이언트에는 무엇이 잘못됐고 어떻게 고치는지만 알려준다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://paycore.kr/problems/";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBodyValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "요청 본문 검증 실패");
        pd.setProperty("errors", errors);
        log.warn("요청 검증 실패 errors={}", errors);
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onParamValidation(ConstraintViolationException e) {
        List<Map<String, String>> errors = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "요청 파라미터 검증 실패");
        pd.setProperty("errors", errors);
        log.warn("파라미터 검증 실패 errors={}", errors);
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException e) {
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            return problem(HttpStatus.BAD_REQUEST, ErrorCode.IDEMPOTENCY_KEY_REQUIRED, null);
        }
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "필수 헤더 누락: " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("본문 파싱 실패: {}", e.getMostSpecificCause().toString());
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "요청 본문을 해석할 수 없습니다.");
    }

    /**
     * 같은 키 + 다른 본문. 422 를 쓰는 이유: 문법은 맞지만(400 아님) 이전 요청과 모순되므로 처리할 수 없다.
     */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail onIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        log.warn("Idempotency-Key 재사용 감지 key={}", e.idempotencyKey());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED, null);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail onNotFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PAYMENT_NOT_FOUND, null);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        // 유일하게 스택트레이스를 남기는 지점. 응답에는 아무 내부 정보도 싣지 않는다.
        log.error("처리되지 않은 예외", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, null);
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail != null ? detail : code.message());
        pd.setType(URI.create(TYPE_PREFIX + code.code().toLowerCase()));
        pd.setTitle(code.message());
        pd.setProperty("code", code.code());
        pd.setProperty("timestamp", clock.instant().toString());
        return pd;
    }
}
