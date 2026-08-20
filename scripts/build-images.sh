#!/usr/bin/env bash
# 모든 서비스의 bootJar 를 만들고 docker 이미지를 빌드한다.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> gradlew bootJar"
./gradlew bootJar -x test

echo "==> docker compose build"
docker compose build payment-api clearing-gateway clearing-simulator ledger-service recon-batch

echo "==> done"
docker images --filter "reference=paycore/*" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"
