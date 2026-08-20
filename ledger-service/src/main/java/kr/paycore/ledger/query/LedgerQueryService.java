package kr.paycore.ledger.query;

import java.util.List;
import java.util.Optional;
import kr.paycore.core.ledger.Journal;
import kr.paycore.core.ledger.JournalRepository;
import kr.paycore.core.ledger.LedgerEntry;
import kr.paycore.core.ledger.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원장 조회 (docs §5.5 — 잔액은 저장하지 않고 명세에서 유도한다). */
@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    private final JournalRepository journals;
    private final LedgerEntryRepository entries;

    public LedgerQueryService(JournalRepository journals, LedgerEntryRepository entries) {
        this.journals = journals;
        this.entries = entries;
    }

    public Optional<JournalView> findByPaymentId(String paymentId) {
        return journals.findByPaymentId(paymentId).map(this::toView);
    }

    public AccountBalanceView balanceOf(String accountId) {
        long debit = entries.debitTotal(accountId);
        long credit = entries.creditTotal(accountId);
        return new AccountBalanceView(accountId, debit, credit, credit - debit);
    }

    /** 전체 원장 불균형. 0 이 아니면 복식부기가 깨진 것이고, 그 자체로 사고다. */
    public long globalImbalance() {
        return entries.globalImbalance();
    }

    private JournalView toView(Journal journal) {
        List<EntryView> lines = entries.findByJournalIdOrderByDrCrAsc(journal.journalId()).stream()
                .map(e -> new EntryView(e.entryId(), e.accountId(), e.drCr().name(), e.amount()))
                .toList();
        long imbalance = entries.findByJournalIdOrderByDrCrAsc(journal.journalId()).stream()
                .mapToLong(LedgerEntry::signed)
                .sum();
        return new JournalView(journal.journalId(), journal.paymentId(), journal.postedAt(), lines, imbalance);
    }
}
