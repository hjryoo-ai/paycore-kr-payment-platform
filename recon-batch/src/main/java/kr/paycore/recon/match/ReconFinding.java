package kr.paycore.recon.match;

import kr.paycore.core.recon.BreakType;

/**
 * 대사가 찾아낸 불일치 하나.
 *
 * @param paymentId 우리 DB 에 없는 건이면 {@code null} — 그런 건이야말로 반드시 남겨야 한다
 * @param detail 운영자가 <b>어디부터 볼지</b> 알 수 있게 양쪽 주장을 함께 적는다
 */
public record ReconFinding(BreakType type, String paymentId, String key, String detail) {}
