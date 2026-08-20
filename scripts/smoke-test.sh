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
TOTAL_BREAKS=$(recon_run | json_field openBreaks)

# 단정은 **이번에 흘려보낸 건**에 한정한다. 전체 건수로 단정하면 이 스모크는 빈 DB 에서만
# 통과한다 — 이전 실행이 남긴 결제나 일부러 만든 장애 시나리오 흔적이 있으면 무조건 실패한다.
# "내가 방금 밀어 넣은 결제가 세 출처에서 일치하는가"가 스모크가 실제로 물어야 하는 질문이다.
MINE=$(recon_breaks_for "$PID" || true)
if [ -n "$MINE" ]; then
    bad "방금 흘려보낸 결제에 불일치가 있다: $MINE"
    exit 1
fi
ok "이번 결제의 대사 불일치 0건 (전체 미해결 ${TOTAL_BREAKS}건 — 이전 실행 흔적 포함)"

print_timeline "$PID"
sim_reset
ok "스모크 통과: 접수 → 청산 → 원장 → 대사까지 한 건이 온전히 흘렀다"
