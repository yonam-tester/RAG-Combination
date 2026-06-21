#!/usr/bin/env bash
set -euo pipefail

DEMO_ENV="${HOME}/.demo.env"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ~/.demo.env 존재 여부 확인
if [ ! -f "$DEMO_ENV" ]; then
  echo "오류: ~/.demo.env 파일이 없습니다."
  echo ""
  echo "아래 형식으로 생성하세요 (scripts/.demo.env.example 참고):"
  echo "  EC2_HOST=3.35.203.154"
  echo "  EC2_PEM=~/Downloads/yonam-test.pem"
  echo "  LLM_API_KEY=sk-..."
  exit 1
fi

# shellcheck source=/dev/null
source "$DEMO_ENV"

EC2_HOST="${EC2_HOST:?EC2_HOST가 ~/.demo.env에 없습니다}"
EC2_PEM="${EC2_PEM:?EC2_PEM이 ~/.demo.env에 없습니다}"
LLM_API_KEY="${LLM_API_KEY:?LLM_API_KEY가 ~/.demo.env에 없습니다}"

# ~ 경로 확장
EC2_PEM="${EC2_PEM/#\~/$HOME}"

SSH_CMD="ssh -i ${EC2_PEM} -o StrictHostKeyChecking=no ubuntu@${EC2_HOST}"

MODE="${1:-}"

case "$MODE" in
  --preflight)
    echo "=== 사전 점검 ==="
    $SSH_CMD "cd /home/ubuntu/app && bash scripts/verify.sh"
    ;;

  --run)
    echo "=== 사전 점검 ==="
    $SSH_CMD "cd /home/ubuntu/app && bash scripts/verify.sh"

    echo ""
    echo "=== LLM vs RAG 평가 실행 중 (약 2-3분) ==="
    $SSH_CMD "cd /home/ubuntu/app && \
      LLM_API_KEY='${LLM_API_KEY}' \
      LLM_SERVER_URL=http://localhost:8001 \
      RAG_SERVER_URL=http://localhost:8000 \
      PUSHGATEWAY_URL=http://localhost:9091 \
      python3 evaluation/eval_runner.py"

    echo ""
    echo "=== Grafana 대시보드 오픈 ==="
    open "http://${EC2_HOST}:3000/d/yeonam-overview"

    echo ""
    echo "=== 대시보드 탐색 순서 ==="
    echo "1. [LLM vs RAG 정확도 비교] — RAG 정확도가 더 높은지 확인"
    echo "2. [LLM-as-Judge 점수]     — RAG 품질 점수 비교"
    echo "3. [토큰 사용량]            — RAG가 더 많은 컨텍스트를 활용함"
    echo "4. [컨테이너 CPU/메모리]    — 실제 리소스 소비 확인"
    echo "5. [테스트 커버리지]        — CI 파이프라인 연동 확인"
    ;;

  *)
    echo "사용법: $0 [--preflight | --run]"
    echo ""
    echo "  --preflight  서비스 상태 사전 점검만 실행 (EC2 SSH)"
    echo "  --run        점검 → eval 실행 → Grafana 브라우저 자동 오픈"
    echo ""
    echo "사전 조건: ~/.demo.env 파일에 EC2_HOST, EC2_PEM, LLM_API_KEY 설정 필요"
    echo "           (scripts/.demo.env.example 참고)"
    exit 1
    ;;
esac
