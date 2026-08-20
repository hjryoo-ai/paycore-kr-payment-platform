#!/usr/bin/env bash
# 시나리오 #7 — poison message (docs §8, §7.5)
#
# 기대 결과
#   깨진 payload 는 재시도 없이 DLT 로 밀려나 운영자 워크리스트에 올라간다.
#   같은 파티션의 뒤이은 정상 결제는 막히지 않고 계속 흐른다.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

require_stack
sim_reset

log "정상 결제를 먼저 흘려보내 기준선을 만든다"
BEFORE=$(submit_payment 1300000)
wait_for_status "$BEFORE" SETTLED 120

log "깨진 payload 를 payment.events 에 직접 주입한다"
docker exec paycore-kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic payment.events \
    --property "parse.headers=true" --property "headers.delimiter=\t" --property "headers.separator=," \
    --property "parse.key=true" --property "key.separator=|" \
    <<<'eventType:PaymentSettled,eventId:chaos-poison-1	01M0CHAOSPOISON000000000AA|{이건 JSON 이 아니다'

log "DLT 워크리스트 확인"
DEADLINE=$(( $(date +%s) + 60 ))
while :; do
    FOUND=$(curl -fsS "$API/api/v1/ops/dead-letters?status=NEW" \
        | python3 -c 'import sys,json
for d in json.load(sys.stdin):
    if d.get("eventId") == "chaos-poison-1":
        print("%s|%s|%s" % (d["deadLetterId"], d["exceptionType"], d["status"]))' || true)
    [ -n "$FOUND" ] && break
    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        bad "60s 안에 DLT 워크리스트에 올라오지 않았다"
        curl -fsS "$API/api/v1/ops/dead-letters" | python3 -m json.tool >&2
        exit 1
    fi
    sleep 2
done
ok "DLT 적재됨"
printf '   %s\n' "$FOUND"
DLT_ID="${FOUND%%|*}"

log "poison 뒤에 온 정상 결제가 막히지 않는지 확인"
AFTER=$(submit_payment 1400000)
wait_for_status "$AFTER" SETTLED 120
ok "뒤이은 결제가 정상 처리됐다 — 파티션이 막히지 않았다"

log "운영자가 원인을 확인하고 폐기 처리 (자동 재주입은 금지다)"
curl -fsS -X POST "$API/api/v1/ops/dead-letters/$DLT_ID/discard" \
    -H 'Content-Type: application/json' -H 'X-Operator: chaos.operator' \
    -d '{"reason":"장애 주입 스크립트가 만든 메시지 — 재처리 불필요"}' | python3 -m json.tool

log "감사 기록"
curl -fsS "$API/api/v1/ops/audit?targetType=DEAD_LETTER&targetId=$DLT_ID" | python3 -m json.tool

sim_reset
ok "시나리오 #7 통과: 재시도 없이 DLT, 다른 메시지 처리 계속, 운영 조치가 감사에 남음"
