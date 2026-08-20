package kr.paycore.core.process;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 수취은행 라우팅 (docs §5.2).
 *
 * <p>현재는 "이 은행코드가 전자금융공동망으로 보낼 수 있는 코드인가"만 판단한다. 실제 시스템이라면 여기서
 * 망 구분(공동망/한은금융망/타행이체), 마감시각, 은행별 영업 상태까지 갈린다. 그 확장 지점을 남겨 두려고
 * payment-api 의 입력 검증과 별도 컴포넌트로 분리했다.
 */
@Component
public class ClearingRouter {

    private final Set<String> routableBankCodes;

    public ClearingRouter(@Value("${paycore.core.routable-bank-codes:}") Set<String> routableBankCodes) {
        this.routableBankCodes = routableBankCodes;
    }

    /** 설정이 비어 있으면 payment-api 의 화이트리스트를 이미 통과한 것으로 보고 모두 라우팅 가능으로 둔다. */
    public boolean isRoutable(String bankCode) {
        return routableBankCodes.isEmpty() || routableBankCodes.contains(bankCode);
    }
}
