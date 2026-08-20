#!/usr/bin/env bash
# 시나리오 #8 — UNKNOWN 을 방치한 채 일마감 (docs §8, §5.6)
#
# 기대 결과
#   청산망은 처리했는데 우리는 결론을 못 낸 건이 MISSING_AT_US 로 검출된다.
#   요약 리포트(md)에 조사 순서까지 적힌다.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

require_stack
sim_reset

# 1) 청산망은 처리하되 응답을 보내지 않는다.
sim_mode PROCESS_BUT_NO_RESPONSE
log "이체 접수"
PID=$(submit_payment 2600000)
E2E=$(payment_end_to_end "$PID")
log "paymentId=$PID endToEndId=$E2E"
wait_for_clearing_record "$E2E" 60

# 2) 그 뒤 청산망을 세운다 — 상태조회도 답을 받지 못해 UNKNOWN 이 그대로 남는다.
sim_mode DOWN
wait_for_status "$PID" UNKNOWN 90
log "돈은 나갔는데 우리는 모른다 — 이 상태로 마감을 맞는다"

# 3) 청산망 EOD 생성 (그 건이 ACSC 로 들어 있다) → 대사 실행
log "청산망 EOD 생성"
curl -fsS -X POST "$SIM/simulator/eod?date=$(business_date)" | python3 -m json.tool

log "일마감 대사 실행"
recon_run | python3 -m json.tool

FOUND=$(recon_breaks_for "$PID" || true)
if printf '%s' "$FOUND" | grep -q '^MISSING_AT_US|'; then
    ok "MISSING_AT_US 로 검출됐다"
    printf '   %s\n' "$FOUND"
else
    bad "MISSING_AT_US 가 없다. 검출된 것: ${FOUND:-없음}"; exit 1
fi

print_timeline "$PID"

log "복구"
sim_reset
ok "시나리오 #8 통과: 방치된 UNKNOWN 이 대사에서 MISSING_AT_US 로 잡혔다"
log "참고: 복구 후 밀려 있던 pacs.028 이 처리되어 이 건은 곧 CLEARED 로 확정된다 — 재대사하면 불일치가 닫힌다"
