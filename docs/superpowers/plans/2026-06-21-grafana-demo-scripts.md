# Grafana 검증 및 데모 자동화 스크립트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EC2 관측성 스택을 자동 점검하는 `verify.sh`와 면접/포트폴리오 라이브 시연을 한 번의 명령으로 실행하는 `demo.sh`를 구현한다.

**Architecture:** `verify.sh`는 EC2 내부에서 직접 실행되어 6개 서비스 상태를 curl로 점검하고 컬러 PASS/FAIL을 출력한다. `demo.sh`는 Mac에서 실행되어 `~/.demo.env`의 자격증명을 로드하고 SSH로 EC2에 접속해 사전 점검 → eval 실행 → 브라우저 오픈 순으로 진행한다.

**Tech Stack:** bash, curl, docker compose, python3, SSH, macOS `open`

## Global Constraints

- 모든 스크립트: `#!/usr/bin/env bash` + `set -euo pipefail`
- PASS = 초록 ANSI (`\033[0;32m`), FAIL = 빨강 ANSI (`\033[0;31m`), 리셋 = `\033[0m`
- 자격증명은 `~/.demo.env`에서만 로드 (EC2_HOST, EC2_PEM, LLM_API_KEY) — 스크립트 내 하드코딩 금지
- verify.sh: 하나라도 FAIL 시 `exit 1`
- demo.sh: `--preflight` (점검만), `--run` (full demo), 인자 없음 (사용법 출력 후 `exit 1`)
- EC2 앱 경로: `/home/ubuntu/app`
- SSH 옵션: `-o StrictHostKeyChecking=no`

---

### Task 1: verify.sh — EC2 서비스 Health Check 스크립트

**Files:**
- Create: `scripts/verify.sh`

**Interfaces:**
- Consumes: 없음 (독립 실행)
- Produces: 성공 시 exit 0, 실패 시 exit 1. Task 2의 demo.sh가 SSH를 통해 이 스크립트를 호출한다.

- [ ] **Step 1: scripts/ 디렉토리 확인**

```bash
ls scripts/ 2>/dev/null || echo "scripts/ 디렉토리 없음 — mkdir 필요"
```

없으면:
```bash
mkdir -p scripts
```

- [ ] **Step 2: verify.sh 작성**

`scripts/verify.sh`를 아래 내용으로 생성한다:

```bash
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
  total=$(docker compose -f "$APP_DIR/docker-compose.yml" ps --status running 2>/dev/null | grep -c "running" || true)
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
```

- [ ] **Step 3: 실행 권한 부여**

```bash
chmod +x scripts/verify.sh
```

- [ ] **Step 4: 문법 검사**

```bash
bash -n scripts/verify.sh
```

Expected: 아무 출력 없음 (문법 오류 없음)

- [ ] **Step 5: 로컬에서 출력 형태 확인 (서비스 없이 FAIL 경로 테스트)**

```bash
bash scripts/verify.sh || true
```

Expected: 각 점검 항목이 `[✗]` + 힌트 메시지로 출력되고 마지막에 "N개 점검 실패" 출력. 컬러가 터미널에 렌더링되는지 확인.

- [ ] **Step 6: 커밋**

```bash
git add scripts/verify.sh
git commit -m "feat: Grafana 스택 health check 스크립트 추가 (verify.sh)"
```

---

### Task 2: demo.sh + .demo.env.example — Mac 로컬 시연 스크립트

**Files:**
- Create: `scripts/demo.sh`
- Create: `scripts/.demo.env.example`

**Interfaces:**
- Consumes: Task 1의 `scripts/verify.sh` (EC2에서 SSH로 호출)
- Produces: 없음 (최종 산출물)

- [ ] **Step 1: .demo.env.example 작성**

`scripts/.demo.env.example`를 아래 내용으로 생성한다:

```bash
# ~/.demo.env — 데모 실행 자격증명 (홈 디렉토리에 저장, 절대 커밋하지 말 것)
EC2_HOST=3.35.203.154
EC2_PEM=~/Downloads/yonam-test.pem
LLM_API_KEY=sk-여기에_실제_키_입력
```

- [ ] **Step 2: demo.sh 작성**

`scripts/demo.sh`를 아래 내용으로 생성한다:

```bash
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
```

- [ ] **Step 3: 실행 권한 부여**

```bash
chmod +x scripts/demo.sh
```

- [ ] **Step 4: 문법 검사**

```bash
bash -n scripts/demo.sh
```

Expected: 아무 출력 없음

- [ ] **Step 5: 인자 없이 실행 — 사용법 출력 확인**

```bash
bash scripts/demo.sh || true
```

Expected:
```
사용법: scripts/demo.sh [--preflight | --run]

  --preflight  서비스 상태 사전 점검만 실행 (EC2 SSH)
  --run        점검 → eval 실행 → Grafana 브라우저 자동 오픈
...
```

- [ ] **Step 6: ~/.demo.env 없는 경우 에러 메시지 확인**

```bash
DEMO_ENV_ORIG="$HOME/.demo.env"
# 임시로 파일명 바꿔서 테스트 (실제 파일 없을 때)
bash -c 'HOME=/tmp bash scripts/demo.sh --preflight' || true
```

Expected:
```
오류: ~/.demo.env 파일이 없습니다.
...스크립트 생성 안내 메시지...
```

- [ ] **Step 7: .gitignore에 .demo.env 추가 확인**

프로젝트 루트 `.gitignore`에 이미 `.env` 패턴이 있는지 확인:

```bash
grep -n "\.env" .gitignore || echo "없음"
```

없으면 추가:
```bash
echo ".demo.env" >> .gitignore
```

- [ ] **Step 8: 커밋**

```bash
git add scripts/demo.sh scripts/.demo.env.example .gitignore
git commit -m "feat: 라이브 데모 및 포트폴리오 시연 스크립트 추가 (demo.sh)"
```

---

## 사전 수동 작업 (스크립트 구현 전 필요)

스크립트 구현 후 실제 사용 전 아래 작업이 필요하다:

1. **EC2 보안 그룹**: AWS 콘솔 → EC2 → 보안 그룹 → 인바운드 규칙 → TCP 3000 추가 (소스: 0.0.0.0/0)
2. **~/.demo.env 생성**: `scripts/.demo.env.example`을 참고해 로컬 홈 디렉토리에 생성
3. **초기 eval 실행**: `./scripts/demo.sh --run`을 한 번 실행해 Pushgateway에 초기 메트릭 적재 (첫 `--preflight`에서 Pushgateway 점검이 통과하려면 필요)
