#!/usr/bin/env bash
# 시나리오 #3 — 응답 timeout 이고 청산망은 실제로 받지 못함 (docs §8, ADR-0009)
#
# 기대 결과
#   상태:      RECEIVED → VALIDATED → SENT_TO_CLEARING → UNKNOWN → FAILED
#   청산망:    이 endToEndId 를 모른다 (돈이 나가지 않았다)
#   재송신:    허용됨(resendPermitted) — 단, 자동으로 하지는 않는다
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

require_stack
sim_reset
sim_mode DROP_REQUEST

log "이체 접수"
PID=$(submit_payment 3000000)
E2E=$(payment_end_to_end "$PID")
log "paymentId=$PID endToEndId=$E2E"

wait_for_status "$PID" UNKNOWN 60
log "상태조회가 '받은 적 없음(NOOR)' 이라고 답해야 FAILED 를 확정할 수 있다"
wait_for_status "$PID" FAILED 180

if clearing_knows "$E2E"; then
    bad "청산망에 기록이 있다 — DROP_REQUEST 모드가 동작하지 않았다"; exit 1
else
    ok "청산망은 이 이체를 모른다 — 돈이 나가지 않았다"
fi

print_timeline "$PID"
sim_reset
ok "시나리오 #3 통과: UNKNOWN → inquiry(NOOR) → FAILED 확정"
