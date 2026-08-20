package kr.paycore.ledger.posting;

import java.time.Clock;
import java.util.List;
import kr.paycore.common.id.Ids;
import kr.paycore.common.mask.AccountMasker;
import kr.paycore.core.event.PaymentClearedEvent;
import kr.paycore.core.event.PaymentEventType;
import kr.paycore.core.event.PaymentSettledEvent;
import kr.paycore.core.inbox.InboxGuard;
import kr.paycore.core.ledger.DrCr;
import kr.paycore.core.ledger.Journal;
import kr.paycore.core.ledger.JournalRepository;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.core.ledger.LedgerEntryRepository;
import kr.paycore.core.outbox.OutboxWriter;
import kr.paycore.ledger.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 복식부기 기표 (docs §5.5).
 *
 * <p>이체 1건은 분개 1벌(명세 2줄)이 된다.
 *
 * <pre>
 *   차변(D)  고객 출금계좌      1,500,000   ← 고객의 예금이 줄어든다
 *   대변(C)  청산미결제 계정    1,500,000   ← 청산망에 갚아야 할 금액이 늘어난다
 * </pre>
 *
 * <p>멱등성 방어는 2중이다(docs §7.2). ① 기술 키 — {@code PROCESSED_MESSAGE} inbox 로 같은 메시지를
 * 두 번 처리하지 않는다. ② 비즈니스 키 — 메시지 ID 가 달라도(재발행) {@code JOURNAL.PAYMENT_ID} 가
 * 같으면 막힌다. 크래시 후 재소비되어도 분개는 정확히 한 벌이다(시나리오 #5).
 */
@Service
public class LedgerPostingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final JournalRepository journals;
    private final LedgerEntryRepository entries;
    private final InboxGuard inbox;
    private final OutboxWriter outbox;
    private final LedgerProperties properties;
    private final Ids ids;
    private final Clock clock;

    public LedgerPostingService(
            JournalRepository journals,
            LedgerEntryRepository entries,
            InboxGuard inbox,
            OutboxWriter outbox,
            LedgerProperties properties,
            Ids ids,
            Clock clock) {
        this.journals = journals;
        this.entries = entries;
        this.inbox = inbox;
        this.outbox = outbox;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * 청산 완료된 결제를 원장에 반영한다.
     *
     * @param eventId 아웃박스 이벤트 ID — inbox dedup 키
     * @return 새로 기표했으면 true, 이미 반영된 건이라 아무것도 하지 않았으면 false
     */
    @Transactional
    public boolean post(String eventId, PaymentClearedEvent event) {
        if (!inbox.claim(properties.consumerGroup(), eventId)) {
            return false;
        }
        // 비즈니스 키 방어. UNIQUE 위반을 잡는 대신 미리 확인하는 이유는, 제약 위반이 나면 트랜잭션
        // 전체가 rollback-only 로 오염되어 inbox 기록까지 함께 사라지기 때문이다.
        if (journals.existsByPaymentId(event.paymentId())) {
            log.info("이미 기표된 결제 — 분개를 만들지 않는다 paymentId={}", event.paymentId());
            return false;
        }

        String journalId = ids.newEventId();
        journals.save(new Journal(journalId, event.paymentId(), clock.instant()));

        List<LedgerEntry> lines = List.of(
                new LedgerEntry(ids.newEventId(), journalId, event.debtorAccount(), DrCr.D, event.amount()),
                new LedgerEntry(ids.newEventId(), journalId, properties.suspenseAccount(), DrCr.C, event.amount()));

        long imbalance = lines.stream().mapToLong(LedgerEntry::signed).sum();
        if (imbalance != 0) {
            // 도달할 수 없어야 하는 분기다. 그래도 두는 이유: 이 검증이 사라지는 순간을 테스트가 잡아낸다.
            throw new UnbalancedJournalException(event.paymentId(), imbalance);
        }
        entries.saveAll(lines);

        outbox.append(
                event.paymentId(),
                PaymentEventType.PAYMENT_SETTLED,
                new PaymentSettledEvent(
                        event.paymentId(),
                        event.endToEndId(),
                        journalId,
                        event.amount(),
                        event.currency(),
                        clock.instant()));

        log.info(
                "기표 완료 paymentId={} journalId={} 차변={} 대변={} 금액={}",
                event.paymentId(),
                journalId,
                AccountMasker.mask(event.debtorAccount()),
                properties.suspenseAccount(),
                event.amount());
        return true;
    }
}
