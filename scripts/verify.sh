#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

PASS="${GREEN}[✓]${NC}"
FAIL="${RED}[✗]${NC}"
ERRORS=0
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"

check_containers() {
  local total
  total=$(docker compose -f "$APP_DIR/docker-compose.yml" ps --status running 2>/dev/null | grep -c " Up " || true)
  local expected=9
  if [ "$total" -ge "$expected" ]; then
    echo -e "$PASS 컨테이너 (${total}/${expected} Up)"
  else
    echo -e "$FAIL 컨테이너 (${total}/${expected} Up) → docker compose up -d 실행 필요"
    ERRORS=$((ERRORS+1))
  fi
}

check_grafana() {
  local resp
  resp=$(curl -sf --max-time 5 "http://localhost:3000/api/health" 2>/dev/null || echo "")
  if echo "$resp" | grep -q '"database":"ok"'; then
    echo -e "$PASS Grafana healthy"
  else
    echo -e "$FAIL Grafana unhealthy → docker compose restart grafana"
    ERRORS=$((ERRORS+1))
  fi
}

check_llm() {
  local resp
  resp=$(curl -sf --max-time 5 "http://localhost:8001/health" 2>/dev/null || echo "")
  if echo "$resp" | grep -q '"status":"healthy"'; then
    echo -e "$PASS LLM-server healthy"
  else
    echo -e "$FAIL LLM-server unhealthy → docker compose restart llm-server"
    ERRORS=$((ERRORS+1))
  fi
}

check_rag() {
  local resp
  resp=$(curl -sf --max-time 5 "http://localhost:8000/health" 2>/dev/null || echo "")
  if echo "$resp" | grep -q '"status":"healthy"'; then
    echo -e "$PASS RAG-server healthy"
  else
    echo -e "$FAIL RAG-server unhealthy → docker compose restart rag-server"
    ERRORS=$((ERRORS+1))
  fi
}

check_pushgateway() {
  local resp
  resp=$(curl -sf --max-time 5 "http://localhost:9091/metrics" 2>/dev/null || echo "")
  if echo "$resp" | grep -q "eval_accuracy"; then
    echo -e "$PASS Pushgateway eval 데이터 존재"
  else
    echo -e "$FAIL Pushgateway eval 데이터 없음 → ./scripts/demo.sh --run 으로 eval_runner.py를 먼저 실행하세요"
    ERRORS=$((ERRORS+1))
  fi
}

check_nginx() {
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:80" 2>/dev/null || echo "000")
  if [ "$code" = "200" ]; then
    echo -e "$PASS nginx 응답 200"
  else
    echo -e "$FAIL nginx 응답 ${code} → docker compose restart nginx"
    ERRORS=$((ERRORS+1))
  fi
}

main() {
  echo ""
  check_containers
  check_grafana
  check_llm
  check_rag
  check_pushgateway
  check_nginx
  echo ""
  if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}모든 점검 통과 — 데모 준비 완료!${NC}"
    exit 0
  else
    echo -e "${RED}${ERRORS}개 점검 실패 — 위 힌트를 따라 수정 후 재실행하세요.${NC}"
    exit 1
  fi
}

main
