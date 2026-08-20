#!/usr/bin/env bash
# compose 서비스가 모두 healthy 가 될 때까지 대기한다. 사용법: scripts/wait-for-healthy.sh [timeout_sec]
# macOS 기본 bash 3.2 에서도 동작하도록 mapfile/연관배열을 쓰지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."

TIMEOUT="${1:-600}"
DEADLINE=$(( $(date +%s) + TIMEOUT ))

while :; do
    STATUS=$(docker compose ps --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null || true)

    if [ -z "$STATUS" ]; then
        echo "기동된 컨테이너가 없습니다. 먼저 'docker compose up -d' 를 실행하세요." >&2
        exit 1
    fi

    PENDING=$(printf '%s\n' "$STATUS" | awk -F'|' '$3 != "healthy" { printf "%s(%s/%s) ", $1, $2, ($3=="" ? "no-healthcheck" : $3) }')

    if [ -z "$PENDING" ]; then
        echo "모든 컨테이너 healthy"
        docker compose ps
        exit 0
    fi

    if [ "$(date +%s)" -ge "$DEADLINE" ]; then
        echo "TIMEOUT (${TIMEOUT}s). 대기 중이던 서비스: $PENDING" >&2
        docker compose ps
        exit 1
    fi

    echo "대기 중: $PENDING"
    sleep 5
done
