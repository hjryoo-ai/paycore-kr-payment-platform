#!/usr/bin/env bash
# 시나리오 #4 — 같은 pacs.002 가 두 번 도착 (docs §8, §7.2)
#
# 기대 결과
#   상태 전이 1회 (CLEARED 가 이력에 한 번만)
#   청산망 처리 1건
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

require_stack
sim_reset
sim_mode DUPLICATE_RESPONSE

log "이체 접수"
PID=$(submit_payment 1200000)
E2E=$(payment_end_to_end "$PID")
log "paymentId=$PID endToEndId=$E2E"

wait_for_status "$PID" CLEARED 90

CLEARED_COUNT=$(curl -fsS "$API/api/v1/payments/$PID" \
    | python3 -c 'import sys,json;print(sum(1 for h in json.load(sys.stdin)["history"] if h["to"]=="CLEARED"))')

if [ "$CLEARED_COUNT" = "1" ]; then
    ok "CLEARED 전이는 1회뿐이다 (중복 응답을 inbox 가 흡수했다)"
else
    bad "CLEARED 전이가 ${CLEARED_COUNT}회 기록됐다 — 소비자 멱등성이 깨졌다"; exit 1
fi

print_timeline "$PID"
sim_reset
ok "시나리오 #4 통과: 중복 응답에도 상태 전이 1회"
