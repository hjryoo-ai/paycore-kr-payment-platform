package kr.paycore.simulator.clearing;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 시뮬레이터가 처리한 이체 기록. <b>endToEndId 가 키</b>인 것이 이 클래스의 전부다 —
 * 실제 청산망의 중복 방어를 재현한다(docs §5.4, §7.3 최후 방어선 ①).
 *
 * <p>메모리 저장이다. 시뮬레이터는 '상대편'이라 우리 스키마를 공유하지 않으며, 재기동하면 기억을
 * 잃는 것도 의도된 단순화다(README 단순화 선언).
 */
@Component
public class TransferStore {

    private final Map<String, ProcessedTransfer> byEndToEndId = new ConcurrentHashMap<>();

    /**
     * 처음 보는 이체면 기록하고 {@link Optional#empty()}, 이미 처리한 이체면 <b>기존 기록</b>을 돌려준다.
     * 반환값이 비어있지 않다는 것은 곧 "DUPL 로 거절해야 한다"는 뜻이다.
     */
    public Optional<ProcessedTransfer> recordIfAbsent(ProcessedTransfer transfer) {
        return Optional.ofNullable(byEndToEndId.putIfAbsent(transfer.endToEndId(), transfer));
    }

    public Optional<ProcessedTransfer> find(String endToEndId) {
        return Optional.ofNullable(byEndToEndId.get(endToEndId));
    }

    public List<ProcessedTransfer> all() {
        return byEndToEndId.values().stream()
                .sorted(Comparator.comparing(ProcessedTransfer::processedAt))
                .toList();
    }

    public List<ProcessedTransfer> processedBetween(Instant fromInclusive, Instant toExclusive) {
        return all().stream()
                .filter(t -> !t.processedAt().isBefore(fromInclusive)
                        && t.processedAt().isBefore(toExclusive))
                .toList();
    }

    public int size() {
        return byEndToEndId.size();
    }

    public void clear() {
        byEndToEndId.clear();
    }
}
