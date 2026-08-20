package kr.paycore.recon.source;

/**
 * EOD 파일이 계약을 어겼다.
 *
 * <p>대사에서 이것은 "일부만 읽고 넘어갈" 문제가 아니다. 못 읽은 줄은 곧 "청산망에 없는 건"으로
 * 둔갑해 가짜 불일치를 만든다. 그래서 부분 성공을 허용하지 않고 마감을 세운다.
 */
public class EodFormatException extends RuntimeException {

    public EodFormatException(String message) {
        super(message);
    }

    public EodFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
