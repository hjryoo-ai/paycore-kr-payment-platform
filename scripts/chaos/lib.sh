#!/usr/bin/env bash
# 장애 시나리오 스크립트 공통 함수 (docs/payment-platform-design.md §8)
# macOS 기본 bash 3.2 에서도 동작하도록 연관배열/mapfile 을 쓰지 않는다.
set -euo pipefail

API="${PAYCORE_API:-http://localhost:8081}"
SIM="${PAYCORE_SIM:-http://localhost:8083}"

log()  { printf '\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✔ %s\033[0m\n' "$*"; }
bad()  { printf '\033[1;31m✘ %s\033[0m\n' "$*" >&2; }

require_stack() {
    curl -fsS "$API/actuator/health" >/dev/null || { bad "payment-api 응답 없음 ($API)"; exit 1; }
    curl -fsS "$SIM/actuator/health" >/dev/null || { bad "clearing-simulator 응답 없음 ($SIM)"; exit 1; }
}

json_field() { python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get(sys.argv[1],""))' "$1"; }

sim_mode() {
    local mode="$1"; shift
    local extra="${1:-}"
    local body="{\"mode\":\"$mode\"${extra:+,$extra}}"
    curl -fsS -X PUT "$SIM/simulator/mode" -H 'Content-Type: application/json' -d "$body" >/dev/null
    log "시뮬레이터 모드 → $mode $extra"
}

sim_reset() {
    curl -fsS -X POST "$SIM/simulator/reset" >/dev/null
    log "시뮬레이터 초기화"
}

# 이체 1건 접수. stdout 으로 paymentId 를 돌려준다.
submit_payment() {
    local amount="${1:-1500000}"
    local key
    key=$(uuidgen)
    curl -fsS -X POST "$API/api/v1/payments" \
        -H 'Content-Type: application/json' \
        -H "Idempotency-Key: $key" \
        -d "{\"debtorAccount\":\"110-123-456789\",\"creditorAccount\":\"352-987-654321\",\"creditorBankCode\":\"088\",\"amount\":$amount,\"currency\":\"KRW\",\"remittanceInfo\":\"chaos\"}" \
        | json_field paymentId
}

payment_status()      { curl -fsS "$API/api/v1/payments/$1" | json_field status; }
payment_end_to_end()  { curl -fsS "$API/api/v1/payments/$1" | json_field endToEndId; }

# 상태가 기대값이 될 때까지 기다린다. 사용법: wait_for_status <paymentId> <status> [timeout_sec]
wait_for_status() {
    local id="$1" want="$2" timeout="${3:-120}"
    local deadline=$(( $(date +%s) + timeout )) now
    while :; do
        now=$(payment_status "$id")
        [ "$now" = "$want" ] && { ok "$id → $want"; return 0; }
        if [ "$(date +%s)" -ge "$deadline" ]; then
            bad "$id 가 ${timeout}s 안에 $want 로 가지 않았다 (현재: $now)"
            curl -fsS "$API/api/v1/payments/$id" | python3 -m json.tool >&2
            return 1
        fi
        sleep 2
    done
}

# 청산망(시뮬레이터)이 이 이체를 아는가. 0=안다, 1=모른다
clearing_knows() {
    curl -fsS -o /dev/null "$SIM/simulator/transfers/$1" 2>/dev/null
}

print_timeline() {
    log "상태 타임라인 ($1)"
    curl -fsS "$API/api/v1/payments/$1" | python3 -c '
import sys, json
d = json.load(sys.stdin)
for h in d["history"]:
    frm = h["from"] or "-"
    reason = h["reason"] or ""
    print("   %s  %-16s -> %-16s by %s  %s" % (h["at"], frm, h["to"], h["triggeredBy"], reason))
'
}

RECON="${PAYCORE_RECON:-http://localhost:8085}"

business_date() { TZ=Asia/Seoul date +%Y-%m-%d; }

# 청산망이 이 이체를 처리 기록으로 갖고 있을 때까지 기다린다.
wait_for_clearing_record() {
    local e2e="$1" timeout="${2:-60}"
    local deadline=$(( $(date +%s) + timeout ))
    while :; do
        clearing_knows "$e2e" && { ok "청산망이 $e2e 를 처리했다"; return 0; }
        if [ "$(date +%s)" -ge "$deadline" ]; then
            bad "청산망이 ${timeout}s 안에 $e2e 를 처리하지 않았다"; return 1
        fi
        sleep 1
    done
}

recon_run() {
    curl -fsS -X POST "$RECON/api/v1/recon/run?date=$(business_date)"
}

recon_breaks_for() {
    curl -fsS "$RECON/api/v1/recon/breaks?date=$(business_date)" \
        | python3 -c '
import sys, json
target = sys.argv[1]
for b in json.load(sys.stdin):
    if b.get("paymentId") == target:
        print("%s|%s" % (b["breakType"], b["detail"]))
' "$1"
}
