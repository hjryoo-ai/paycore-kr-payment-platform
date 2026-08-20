package kr.paycore.common.mask;

/**
 * 계좌번호 등 민감 식별자의 로그 마스킹 (docs §5.1, §10.2).
 *
 * <p>규칙: 앞 3자와 뒤 3자만 남기고 나머지 문자는 {@code *} 로 바꾼다. 구분자 {@code -} 는 자릿수 형태를
 * 알아볼 수 있도록 보존한다. 예) {@code 110-123-456789} → {@code 110-***-***789}
 *
 * <p>남는 문자가 6자 이하면 식별에 쓸 수 있을 만큼 노출되므로 전부 마스킹한다.
 */
public final class AccountMasker {

    private static final int HEAD = 3;
    private static final int TAIL = 3;
    private static final char MASK = '*';

    private AccountMasker() {}

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        long significant = trimmed.chars().filter(Character::isLetterOrDigit).count();
        boolean tooShort = significant <= HEAD + TAIL;

        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                sb.append(c); // 구분자는 형태 유지
            } else if (!tooShort && (i < HEAD || i >= trimmed.length() - TAIL)) {
                sb.append(c);
            } else {
                sb.append(MASK);
            }
        }
        return sb.toString();
    }
}
