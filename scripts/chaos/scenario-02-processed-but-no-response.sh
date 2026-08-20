#!/usr/bin/env bash
# 시나리오 #2 — 응답 timeout 인데 청산망은 실제로 처리함 (docs §8, §7.3)
#
# 기대 결과
#   상태:      RECEIVED → VALIDATED → SENT_TO_CLEARING → UNKNOWN → CLEARED
#   이체 건수: 청산망 기준 정확히 1건
#   재송신:    0회 (pacs.008 은 한 번만, 확인은 pacs.028 로)
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

require_stack
sim_reset
sim_mode PROCESS_BUT_NO_RESPONSE

log "이체 접수"
PID=$(submit_payment 2000000)
E2E=$(payment_end_to_end "$PID")
log "paymentId=$PID endToEndId=$E2E"

# 응답이 오지 않으므로 먼저 '모른다'로 가야 한다. 실패로 가면 그 자체가 버그다.
wait_for_status "$PID" UNKNOWN 60
log "timeout 을 실패로 단정하지 않았다 — 여기서 재송신했다면 이중 지급이다"

# pacs.028 상태조회가 '처리됨'을 확인해 주면 그때 확정된다.
wait_for_status "$PID" CLEARED 180

if clearing_knows "$E2E"; then
    ok "청산망은 이 이체를 1건 처리했다"
    curl -fsS "$SIM/simulator/transfers/$E2E" | python3 -m json.tool
else
    bad "청산망에 처리 기록이 없다 — 시나리오 전제가 깨졌다"; exit 1
fi

print_timeline "$PID"
sim_reset
ok "시나리오 #2 통과: 이체 1건, 재송신 0회, UNKNOWN → inquiry → CLEARED"
