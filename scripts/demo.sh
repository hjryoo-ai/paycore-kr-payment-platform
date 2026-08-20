#!/usr/bin/env bash
#
# PayCore-KR 전체 시연 / Full demonstration (docs §12.2 Phase 9)
#
#   1. Happy path      정상 이체 1건: 접수 → 청산 → 원장 → SETTLED
#   2. Scenario #2     응답 유실인데 실제로는 처리됨 → UNKNOWN → 조회 → CLEARED (재송신 0회)
#   3. Scenario #5     원장 consumer 강제 재소비 → 분개는 여전히 1벌
#   4. Scenario #8     UNKNOWN 방치 → EOD 대사에서 MISSING_AT_US
#   5. EOD report      요약 리포트(md) 출력
#
# 전제: docker compose up -d 후 전 컨테이너 healthy.
set -euo pipefail
cd "$(dirname "$0")/chaos"
source ./lib.sh

LEDGER="${PAYCORE_LEDGER:-http://localhost:8084}"

step() { printf '\n\033[1;35m━━━ %s\033[0m\n' "$*"; }

require_stack
sim_reset

# ─────────────────────────────────────────────────────────────────
step "1/5  정상 흐름 — 접수부터 원장 반영까지"
HAPPY=$(submit_payment 1500000)
HAPPY_E2E=$(payment_end_to_end "$HAPPY")
wait_for_status "$HAPPY" SETTLED 180
print_timeline "$HAPPY"
log "청산망도 이 이체를 정확히 1건 안다"
clearing_knows "$HAPPY_E2E" || { bad "청산망에 기록 없음"; exit 1; }
ok "정상 흐름 완료 — 세 출처가 모두 같은 사실을 안다"

# ─────────────────────────────────────────────────────────────────
step "2/5  시나리오 #2 — 응답은 유실됐지만 돈은 나갔다"
log "여기서 재송신하면 이중 지급이다. 시스템은 재송신 대신 pacs.028 로 '처리했느냐'를 묻는다."
sim_mode PROCESS_BUT_NO_RESPONSE
S2=$(submit_payment 2000000)
S2_E2E=$(payment_end_to_end "$S2")
wait_for_status "$S2" UNKNOWN 90
log "timeout 을 실패로 단정하지 않았다 (UNKNOWN = 모른다)"
# CLEARED 를 폴링하지 않는다. 조회 응답이 오면 곧바로 원장까지 흘러 SETTLED 가 되므로
# 폴링 간격 사이에 지나가 버린다. 상태는 사라져도 이력은 남으므로 이력을 근거로 삼는다.
wait_for_transition "$S2" UNKNOWN CLEARED 180
wait_for_status "$S2" SETTLED 180
print_timeline "$S2"

PACS008=$(curl -fsS "$SIM/simulator/transfers/$S2_E2E" >/dev/null && echo 1 || echo 0)
[ "$PACS008" = "1" ] || { bad "청산망 기록 없음"; exit 1; }
ok "이체 1건 · 재송신 0회 · 조회로만 확정"
# 모드만 되돌린다. sim_reset 은 청산망의 처리 기록까지 지워서, 앞 단계의 정상 결제들이
# 마지막 대사에서 'MISSING_AT_CLEARING' 으로 둔갑한다 — 시연이 가짜 불일치를 만들면 안 된다.
sim_mode NORMAL

# ─────────────────────────────────────────────────────────────────
step "3/5  시나리오 #5 — 원장 consumer 를 강제로 재소비시킨다"
S5=$(submit_payment 2500000)
wait_for_status "$S5" SETTLED 180
BEFORE=$(curl -fsS "$LEDGER/api/v1/ledger/journals/$S5" | json_field journalId)
log "분개 journalId=$BEFORE"

log "ledger-service 정지 → 소비자 그룹 오프셋을 처음으로 되감기 → 재기동"
docker compose stop ledger-service >/dev/null 2>&1
docker exec paycore-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --group ledger-service \
    --topic payment.events --reset-offsets --to-earliest --execute >/dev/null 2>&1
docker compose start ledger-service >/dev/null 2>&1
DEADLINE=$(( $(date +%s) + 120 ))
until curl -fsS "$LEDGER/actuator/health" >/dev/null 2>&1; do
    [ "$(date +%s)" -ge "$DEADLINE" ] && { bad "ledger-service 재기동 실패"; exit 1; }
    sleep 2
done

AFTER=$(curl -fsS "$LEDGER/api/v1/ledger/journals/$S5" | json_field journalId)
ENTRIES=$(curl -fsS "$LEDGER/api/v1/ledger/journals/$S5" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["entries"]))')
IMBALANCE=$(curl -fsS "$LEDGER/api/v1/ledger/imbalance" | json_field imbalance)

if [ "$BEFORE" = "$AFTER" ] && [ "$ENTRIES" = "2" ] && [ "$IMBALANCE" = "0" ]; then
    ok "재소비했지만 분개는 그대로다 — journalId 동일, 명세 2줄, 장부 균형 0"
else
    bad "재소비 후 분개가 달라졌다 before=$BEFORE after=$AFTER entries=$ENTRIES imbalance=$IMBALANCE"
    exit 1
fi

# ─────────────────────────────────────────────────────────────────
step "4/5  시나리오 #8 — UNKNOWN 을 방치한 채 마감을 맞는다"
sim_mode PROCESS_BUT_NO_RESPONSE
S8=$(submit_payment 2600000)
S8_E2E=$(payment_end_to_end "$S8")
wait_for_clearing_record "$S8_E2E" 60
sim_mode DOWN
wait_for_status "$S8" UNKNOWN 90
log "돈은 나갔는데 우리는 모른다 — 이 상태로 마감한다"

curl -fsS -X POST "$SIM/simulator/eod?date=$(business_date)" >/dev/null
recon_run | python3 -m json.tool

FOUND=$(recon_breaks_for "$S8" || true)
if printf '%s' "$FOUND" | grep -q '^MISSING_AT_US|'; then
    ok "대사가 잡아냈다: $FOUND"
else
    bad "MISSING_AT_US 미검출: ${FOUND:-없음}"; exit 1
fi

# ─────────────────────────────────────────────────────────────────
step "5/5  EOD 대사 리포트"
docker exec paycore-recon-batch cat "/app/data/recon/recon-$(business_date | tr -d '-').md" 2>/dev/null \
    || log "(리포트 파일을 읽지 못했다 — 컨테이너 경로 확인)"

step "복구"
sim_mode NORMAL
wait_for_status "$S8" SETTLED 180
recon_run | python3 -m json.tool
REMAINING=$(recon_breaks_for "$S8" || true)
[ -z "$REMAINING" ] && ok "복구 후 재대사에서 불일치가 닫혔다" || { bad "불일치가 남았다: $REMAINING"; exit 1; }

sim_reset
printf '\n\033[1;32m━━━ 시연 완료 ━━━\033[0m\n'
cat <<'SUMMARY'
  정상 흐름       접수 → 청산 → 원장 → SETTLED
  시나리오 #2     응답 유실 + 실제 처리됨  → UNKNOWN → 조회 → CLEARED (이체 1건, 재송신 0회)
  시나리오 #5     consumer 강제 재소비     → 분개 1벌 유지 (inbox + JOURNAL UNIQUE)
  시나리오 #8     UNKNOWN 방치 후 마감      → MISSING_AT_US 검출 → 복구 후 자동 해소

  대시보드   http://localhost:8086
  Grafana    http://localhost:3000  (docker compose --profile obs up -d)
SUMMARY
