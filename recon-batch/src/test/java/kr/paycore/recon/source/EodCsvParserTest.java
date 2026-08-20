package kr.paycore.recon.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EOD 파서.
 *
 * <p>깨진 줄을 조용히 건너뛰면 그 건은 "청산망에 없는 것"이 되어 가짜 MISSING_AT_CLEARING 을 만든다.
 * 그래서 부분 성공을 허용하지 않고 예외로 마감을 세운다 — 잘못된 대사 결과보다 멈춘 마감이 낫다.
 */
class EodCsvParserTest {

    private static final String HEADER =
            "endToEndId,msgId,txId,debtorAccount,creditorAccount,creditorBank,amount,currency,status,reason,processedAt";

    @Test
    @DisplayName("정상 CSV 를 읽는다")
    void parsesRows() {
        String csv = HEADER + "\n"
                + "PC-1,M1,T1,110-123-456789,352-987-654321,088,1500000,KRW,ACSC,,2026-08-20T09:00:00Z\n"
                + "PC-2,M2,T2,110-123-456789,352-987-654321,088,900000,KRW,RJCT,AM04,2026-08-20T09:05:00Z\n";

        var records = EodCsvParser.parse(csv);

        assertThat(records).hasSize(2);
        assertThat(records.getFirst().settled()).isTrue();
        assertThat(records.getFirst().reason()).isNull();
        assertThat(records.get(1).rejected()).isTrue();
        assertThat(records.get(1).reason()).isEqualTo("AM04");
        assertThat(records.get(1).amount()).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("따옴표로 감싼 필드의 콤마를 필드 구분자로 착각하지 않는다")
    void handlesQuotedFields() {
        String csv = HEADER + "\n"
                + "PC-1,M1,T1,\"110-123,456789\",352-987-654321,088,1500000,KRW,ACSC,\"a\"\"b\",2026-08-20T09:00:00Z\n";

        var records = EodCsvParser.parse(csv);

        assertThat(records.getFirst().debtorAccount()).isEqualTo("110-123,456789");
        assertThat(records.getFirst().reason()).isEqualTo("a\"b");
    }

    @Test
    @DisplayName("헤더만 있는 파일은 0건이다 — 파일이 없는 것과 다르다")
    void headerOnlyMeansZeroRecords() {
        assertThat(EodCsvParser.parse(HEADER + "\n")).isEmpty();
    }

    @Test
    @DisplayName("빈 파일은 거절한다 — 0건과 구분할 수 없기 때문이다")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> EodCsvParser.parse(""))
                .isInstanceOf(EodFormatException.class)
                .hasMessageContaining("비어 있다");
    }

    @Test
    @DisplayName("헤더가 계약과 다르면 거절한다 — 컬럼이 밀리면 금액과 상태가 뒤바뀐다")
    void rejectsUnexpectedHeader() {
        String csv =
                "endToEndId,msgId,txId,debtorAccount,creditorAccount,creditorBank,currency,amount,status,reason,processedAt\n";

        assertThatThrownBy(() -> EodCsvParser.parse(csv))
                .isInstanceOf(EodFormatException.class)
                .hasMessageContaining("계약과 다르다");
    }

    @Test
    @DisplayName("깨진 줄은 건너뛰지 않고 마감을 세운다")
    void rejectsMalformedRow() {
        String csv =
                HEADER + "\n" + "PC-1,M1,T1,110-123-456789,352-987-654321,088,금액아님,KRW,ACSC,,2026-08-20T09:00:00Z\n";

        assertThatThrownBy(() -> EodCsvParser.parse(csv))
                .isInstanceOf(EodFormatException.class)
                .hasMessageContaining("2행");
    }

    @Test
    @DisplayName("컬럼 수가 모자란 줄도 거절한다")
    void rejectsShortRow() {
        assertThatThrownBy(() -> EodCsvParser.parse(HEADER + "\nPC-1,M1,T1\n"))
                .isInstanceOf(EodFormatException.class)
                .hasMessageContaining("컬럼 수");
    }
}
