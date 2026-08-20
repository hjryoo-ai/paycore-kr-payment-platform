package kr.paycore.api.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.core.domain.Payment;
import kr.paycore.core.domain.PaymentStatus;
import kr.paycore.core.ops.DeadLetter;
import kr.paycore.core.ops.DeadLetterRepository;
import kr.paycore.core.ops.DeadLetterStatus;
import kr.paycore.core.ops.OperationAudit;
import kr.paycore.core.ops.OperationAuditRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 API (docs §5.7 — 운영자가 장애를 처리하는 흐름).
 *
 * <p>운영자 식별은 {@code X-Operator} 헤더다. 실제라면 SSO + 권한 + 4-eyes 승인이 필요하지만,
 * 포트폴리오 범위에서는 <b>누가 했는지가 반드시 남는다</b>는 점만 지킨다(README 단순화 선언).
 * 헤더가 없으면 요청을 거절한다 — 익명 개입을 허용하는 순간 감사 로그가 무의미해진다.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final OpsService opsService;
    private final DeadLetterRepository deadLetters;
    private final OperationAuditRepository audits;

    public OpsController(OpsService opsService, DeadLetterRepository deadLetters, OperationAuditRepository audits) {
        this.opsService = opsService;
        this.deadLetters = deadLetters;
        this.audits = audits;
    }

    public record WorklistItem(
            String paymentId, String endToEndId, String status, long amount, String debtorAccount, Instant updatedAt) {}

    public record DeadLetterView(
            String deadLetterId,
            String originalTopic,
            String eventType,
            String eventId,
            String exceptionType,
            String exceptionMessage,
            String status,
            Instant receivedAt,
            Instant resolvedAt) {}

    public record AuditView(
            Long auditId, String actor, String action, String targetType, String targetId, String detail, Instant at) {}

    public record ActionRequest(@NotBlank @Size(max = 500) String reason) {}

    /** 사람이 봐야 하는 결제 목록. 기본은 MANUAL_REVIEW 다. */
    @GetMapping("/worklist")
    public List<WorklistItem> worklist(@RequestParam(defaultValue = "MANUAL_REVIEW") PaymentStatus status) {
        return opsService.worklist(status).stream().map(OpsController::toItem).toList();
    }

    @PostMapping("/payments/{paymentId}/repair")
    public WorklistItem repair(
            @PathVariable String paymentId,
            @Valid @RequestBody RepairRequest request,
            @RequestHeader("X-Operator") @NotBlank String operator) {
        return toItem(opsService.repair(paymentId, request, operator));
    }

    @GetMapping("/dead-letters")
    public List<DeadLetterView> deadLetters(@RequestParam(required = false) DeadLetterStatus status) {
        List<DeadLetter> found = status == null
                ? deadLetters.findByOrderByReceivedAtAsc()
                : deadLetters.findByStatusOrderByReceivedAtAsc(status);
        return found.stream().map(OpsController::toView).toList();
    }

    @PostMapping("/dead-letters/{deadLetterId}/republish")
    public DeadLetterView republish(
            @PathVariable String deadLetterId,
            @Valid @RequestBody ActionRequest request,
            @RequestHeader("X-Operator") @NotBlank String operator) {
        return toView(opsService.republish(deadLetterId, operator, request.reason()));
    }

    @PostMapping("/dead-letters/{deadLetterId}/discard")
    public DeadLetterView discard(
            @PathVariable String deadLetterId,
            @Valid @RequestBody ActionRequest request,
            @RequestHeader("X-Operator") @NotBlank String operator) {
        return toView(opsService.discard(deadLetterId, operator, request.reason()));
    }

    /** 감사 추적. 대상을 지정하면 그 건의 개입 이력만 시간순으로 준다. */
    @GetMapping("/audit")
    public List<AuditView> audit(
            @RequestParam(required = false) String targetType, @RequestParam(required = false) String targetId) {
        List<OperationAudit> found = targetType == null || targetId == null
                ? audits.findByOrderByCreatedAtDesc()
                : audits.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(targetType, targetId);
        return found.stream()
                .map(a -> new AuditView(
                        a.auditId(), a.actor(), a.action(), a.targetType(), a.targetId(), a.detail(), a.createdAt()))
                .toList();
    }

    private static WorklistItem toItem(Payment payment) {
        return new WorklistItem(
                payment.paymentId(),
                payment.endToEndId(),
                payment.status().name(),
                payment.amount(),
                AccountMasker.mask(payment.debtorAccount()),
                payment.updatedAt());
    }

    private static DeadLetterView toView(DeadLetter entry) {
        return new DeadLetterView(
                entry.deadLetterId(),
                entry.originalTopic(),
                entry.eventType(),
                entry.eventId(),
                entry.exceptionType(),
                entry.exceptionMessage(),
                entry.status().name(),
                entry.receivedAt(),
                entry.resolvedAt());
    }
}
