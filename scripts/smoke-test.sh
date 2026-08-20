#!/usr/bin/env bash
# 배포 후 스모크. "떴는가"가 아니라 **돈이 끝까지 흘렀는가**를 본다.
# 헬스체크만 보는 스모크는 파이프라인이 깨진 채로도 초록불이 켜진다.
set -euo pipefail
cd "$(dirname "$0")/chaos"
source ./lib.sh

require_stack
sim_reset

log "정상 이체 1건 — 접수부터 원장 반영까지"
PID=$(submit_payment 1500000)
E2E=$(payment_end_to_end "$PID")
wait_for_status "$PID" SETTLED 180

log "청산망도 같은 이체를 정확히 1건 안다"
clearing_knows "$E2E" || { bad "청산망에 처리 기록이 없다"; exit 1; }

log "장부가 맞는지"
IMBALANCE=$(curl -fsS "http://localhost:8084/api/v1/ledger/imbalance" | json_field imbalance)
if [ "$IMBALANCE" != "0" ]; then
    bad "장부 불균형: $IMBALANCE"; exit 1
fi
ok "전체 장부 균형 0"

log "일마감 대사"
curl -fsS -X POST "$SIM/simulator/eod?date=$(business_date)" >/dev/null
BREAKS=$(recon_run | json_field openBreaks)
if [ "$BREAKS" != "0" ]; then
    bad "대사 불일치 ${BREAKS}건 — 정상 흐름만 돌렸는데 불일치가 나오면 안 된다"
    curl -fsS "$RECON/api/v1/recon/breaks?date=$(business_date)" | python3 -m json.tool >&2
    exit 1
fi
ok "대사 불일치 0건"

print_timeline "$PID"
sim_reset
ok "스모크 통과: 접수 → 청산 → 원장 → 대사까지 한 건이 온전히 흘렀다"
