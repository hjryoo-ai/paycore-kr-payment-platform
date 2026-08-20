package kr.paycore.common.clearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메시지 계약 테스트. 스키마가 원본이라는 규칙(CLAUDE.md)이 실제로 강제되는지 확인한다 —
 * 규격 위반은 <b>보내기 전에</b> 막혀야 한다.
 */
class ClearingMessageCodecTest {

    private static final Instant AT = Instant.parse("2026-08-20T09:00:00Z");

    private final ClearingMessageCodec codec = new ClearingMessageCodec();

    private static Pacs008 samplePacs008() {
        return new Pacs008(
                new Pacs008.GrpHdr("01M0F70306RVHTGSMZSSNHY4BK", AT, 1, "020", "088"),
                new Pacs008.CdtTrfTxInf(
                        new Pacs008.PmtId("PC01M0F7030849K68J3CVMXCJCTF", "01M0F70306RVHTGSMZSSNHY4BK"),
                        Money.krw(1_500_000L),
                        "110-123-456789",
                        "020",
                        "352-987-654321",
                        "088",
                        "8월 대금"));
    }

    @Test
    @DisplayName("pacs.008 은 왕복(encode → decode) 후에도 값이 같다")
    void pacs008RoundTrip() {
        Pacs008 original = samplePacs008();

        String json = codec.encode(original);
        Pacs008 back = codec.decode(json, Pacs008.class);

        assertThat(back).isEqualTo(original);
        assertThat(json).contains("\"creDtTm\":\"2026-08-20T09:00:00Z\"");
        // 금액은 정수로 나가야 한다 — 1500000.0 이 되면 그 자체로 사고다.
        assertThat(json).contains("\"value\":1500000");
    }

    @Test
    @DisplayName("rmtInf 가 없으면 필드를 아예 내보내지 않는다 (null 리터럴 금지)")
    void omitsNullOptionalFields() {
        Pacs008 original = samplePacs008();
        Pacs008 withoutRmtInf = new Pacs008(
                original.grpHdr(),
                new Pacs008.CdtTrfTxInf(
                        original.cdtTrfTxInf().pmtId(),
                        original.cdtTrfTxInf().intrBkSttlmAmt(),
                        original.cdtTrfTxInf().dbtrAcct(),
                        original.cdtTrfTxInf().dbtrAgt(),
                        original.cdtTrfTxInf().cdtrAcct(),
                        original.cdtTrfTxInf().cdtrAgt(),
                        null));

        assertThat(codec.encode(withoutRmtInf)).doesNotContain("rmtInf");
    }

    @Test
    @DisplayName("스키마를 어긴 메시지는 송신 단계에서 막힌다 — 금액 0")
    void rejectsInvalidAmountOnEncode() {
        Pacs008 original = samplePacs008();
        Pacs008 zeroAmount = new Pacs008(
                original.grpHdr(),
                new Pacs008.CdtTrfTxInf(
                        original.cdtTrfTxInf().pmtId(),
                        Money.krw(0L),
                        original.cdtTrfTxInf().dbtrAcct(),
                        original.cdtTrfTxInf().dbtrAgt(),
                        original.cdtTrfTxInf().cdtrAcct(),
                        original.cdtTrfTxInf().cdtrAgt(),
                        null));

        assertThatThrownBy(() -> codec.encode(zeroAmount))
                .isInstanceOf(ClearingMessageException.class)
                .hasMessageContaining("송신");
    }

    @Test
    @DisplayName("모르는 필드가 섞인 수신 메시지는 거절한다 (additionalProperties: false)")
    void rejectsUnknownFieldOnDecode() {
        String tampered = codec.encode(samplePacs008()).replace("\"rmtInf\"", "\"unknownField\"");

        assertThatThrownBy(() -> codec.decode(tampered, Pacs008.class))
                .isInstanceOf(ClearingMessageException.class)
                .hasMessageContaining("수신");
    }

    @Test
    @DisplayName("JSON 이 아닌 payload 는 예외로 끝난다 — 비즈니스 로직까지 내려가지 않는다")
    void rejectsNonJson() {
        assertThatThrownBy(() -> codec.decode("이건 JSON 이 아니다", Pacs002.class))
                .isInstanceOf(ClearingMessageException.class);
    }

    @Test
    @DisplayName("pacs.002 는 inquiry 응답 여부를 orgnlMsgNmId 로 구분한다")
    void pacs002DistinguishesInquiryAnswer() {
        Pacs002 answerToTransfer = new Pacs002(
                new Pacs002.GrpHdr("01M0F7099RVHTGSMZSSNHY4BK", AT),
                new Pacs002.TxInfAndSts(
                        "01M0F70306RVHTGSMZSSNHY4BK",
                        ClearingMsgType.PACS_008,
                        "PC01M0F7030849K68J3CVMXCJCTF",
                        "01M0F70306RVHTGSMZSSNHY4BK",
                        TxSts.ACSC,
                        null,
                        null));
        Pacs002 answerToInquiry = new Pacs002(
                answerToTransfer.grpHdr(),
                new Pacs002.TxInfAndSts(
                        "01M0F70999RVHTGSMZSSNHY4BK",
                        ClearingMsgType.PACS_028,
                        "PC01M0F7030849K68J3CVMXCJCTF",
                        "01M0F70306RVHTGSMZSSNHY4BK",
                        TxSts.RJCT,
                        StsRsn.NOOR,
                        "원거래 수신 이력 없음"));

        assertThat(codec.decode(codec.encode(answerToTransfer), Pacs002.class).answersInquiry())
                .isFalse();
        assertThat(codec.decode(codec.encode(answerToInquiry), Pacs002.class).answersInquiry())
                .isTrue();
        assertThat(TxSts.ACSP.isFinal()).isFalse();
        assertThat(TxSts.PDNG.isFinal()).isFalse();
        assertThat(TxSts.ACSC.isFinal()).isTrue();
        assertThat(TxSts.RJCT.isFinal()).isTrue();
    }

    @Test
    @DisplayName("pacs.028 은 원 이체지시만 조회 대상으로 삼는다")
    void pacs028RoundTrip() {
        Pacs028 inquiry = new Pacs028(
                new Pacs028.GrpHdr("01M0F70999RVHTGSMZSSNHY4BK", AT),
                new Pacs028.TxInf(
                        "01M0F70306RVHTGSMZSSNHY4BK",
                        ClearingMsgType.PACS_008,
                        "PC01M0F7030849K68J3CVMXCJCTF",
                        "01M0F70306RVHTGSMZSSNHY4BK"));

        assertThat(codec.decode(codec.encode(inquiry), Pacs028.class)).isEqualTo(inquiry);
    }
}
