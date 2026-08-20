package kr.paycore.ledger.query;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원장 조회 API. 운영자가 "이 결제가 장부에 어떻게 들어갔는가"를 확인하는 창구다. */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerQueryService queryService;

    public LedgerController(LedgerQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/journals/{paymentId}")
    public ResponseEntity<JournalView> journal(@PathVariable String paymentId) {
        return queryService.findByPaymentId(paymentId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    @GetMapping("/accounts/{accountId}")
    public AccountBalanceView balance(@PathVariable String accountId) {
        return queryService.balanceOf(accountId);
    }

    /** 전체 장부 균형. 0 이 아니면 즉시 조사 대상이다. */
    @GetMapping("/imbalance")
    public Map<String, Long> imbalance() {
        return Map.of("imbalance", queryService.globalImbalance());
    }
}
