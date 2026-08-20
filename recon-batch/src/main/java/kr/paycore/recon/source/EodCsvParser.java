package kr.paycore.recon.source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * EOD CSV 파서.
 *
 * <p>따옴표 이스케이프까지 처리한다. 대사 입력에서 파싱을 대충 하면, 깨진 한 줄이 조용히 누락되어
 * 존재하지 않는 불일치를 만들어 낸다 — 그 결과를 사람이 몇 시간씩 조사하게 된다.
 */
public final class EodCsvParser {

    /** 시뮬레이터의 {@code EodService.HEADER} 와 같은 순서다. 어긋나면 즉시 예외로 알린다. */
    static final String[] COLUMNS = {
        "endToEndId",
        "msgId",
        "txId",
        "debtorAccount",
        "creditorAccount",
        "creditorBank",
        "amount",
        "currency",
        "status",
        "reason",
        "processedAt"
    };

    private EodCsvParser() {}

    public static List<ClearingEodRecord> parse(String csv) {
        List<String> lines = csv.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new EodFormatException("EOD 파일이 비어 있다 — 헤더조차 없다. 0건과 구분할 수 없으므로 거절한다.");
        }
        List<String> header = splitCsvLine(lines.getFirst());
        if (header.size() != COLUMNS.length) {
            throw new EodFormatException("EOD 헤더 컬럼 수가 다르다: 기대 " + COLUMNS.length + ", 실제 " + header.size());
        }
        for (int i = 0; i < COLUMNS.length; i++) {
            if (!COLUMNS[i].equals(header.get(i))) {
                throw new EodFormatException("EOD 헤더가 계약과 다르다: " + i + "번째가 " + header.get(i));
            }
        }

        List<ClearingEodRecord> records = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> f = splitCsvLine(lines.get(i));
            if (f.size() != COLUMNS.length) {
                throw new EodFormatException((i + 1) + "행의 컬럼 수가 다르다: " + f.size());
            }
            try {
                records.add(new ClearingEodRecord(
                        f.get(0),
                        f.get(1),
                        f.get(2),
                        f.get(3),
                        f.get(4),
                        f.get(5),
                        Long.parseLong(f.get(6)),
                        f.get(7),
                        f.get(8),
                        f.get(9).isBlank() ? null : f.get(9),
                        Instant.parse(f.get(10))));
            } catch (RuntimeException e) {
                throw new EodFormatException((i + 1) + "행을 읽을 수 없다: " + e.getMessage());
            }
        }
        return List.copyOf(records);
    }

    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
