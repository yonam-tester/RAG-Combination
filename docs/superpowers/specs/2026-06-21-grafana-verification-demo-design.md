# Grafana 검증 및 데모 자동화 설계

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** EC2에 배포된 관측성 스택(Grafana + Prometheus + Pushgateway)을 자동으로 검증하고, 면접/포트폴리오 라이브 시연을 한 번의 명령으로 실행할 수 있는 셸 스크립트 2개를 구현한다.

**Architecture:** `scripts/verify.sh`는 EC2 내부에서 실행되어 모든 서비스 상태를 점검하고 컬러 PASS/FAIL을 출력한다. `scripts/demo.sh`는 Mac 로컬에서 실행되어 SSH로 EC2에 접속하고, 사전 점검 → eval 실행 → 브라우저 자동 오픈 순으로 라이브 시연 흐름을 완성한다.

**Tech Stack:** bash, curl, docker compose, python3, SSH, macOS `open` 명령

## Global Constraints

- bash 스크립트: `#!/usr/bin/env bash` + `set -euo pipefail`
- 컬러 출력: PASS = 초록(✓), FAIL = 빨강(✗), tput 미사용 시 ANSI 코드 직접 사용
- 자격증명은 `~/.demo.env` 파일에서만 로드 (EC2_HOST, EC2_PEM, LLM_API_KEY) — 스크립트에 하드코딩 금지
- verify.sh: 하나라도 FAIL 시 exit 1로 즉시 종료
- demo.sh: `--preflight` (점검만), `--run` (full demo) 플래그 지원
- 실행 위치: verify.sh = EC2 `/home/ubuntu/app/`, demo.sh = Mac 로컬 어디서나

---

## 섹션 1: 전체 구조

```
Mac: ./scripts/demo.sh [--preflight | --run]
  └─ ~/.demo.env 로드 (EC2_HOST, EC2_PEM, LLM_API_KEY)
  └─ [1] SSH → EC2: scripts/verify.sh (항상 실행)
  └─ [2] --run 시: SSH → EC2: eval_runner.py 실행 (~2분)
  └─ [3] --run 완료 후: open http://<EC2_HOST>:3000/d/yeonam-overview
  └─ [4] 터미널에 패널 탐색 안내 출력
```

### 사용 예
```bash
# D-5분: 사전 점검만
./scripts/demo.sh --preflight

# 면접 중: 전체 시연
./scripts/demo.sh --run
```

---

## 섹션 2: verify.sh 점검 항목

EC2 `/home/ubuntu/app/` 에서 실행. 6가지를 순서대로 점검.

| # | 점검 대상 | 명령 | 성공 조건 |
|---|---------|------|---------|
| 1 | 컨테이너 상태 | `docker compose ps` | 9개 컨테이너 모두 "Up" 포함 |
| 2 | Grafana | `curl -sf localhost:3000/api/health` | `"database":"ok"` 포함 |
| 3 | LLM-server | `curl -sf localhost:8001/health` | `"status":"healthy"` 포함 |
| 4 | RAG-server | `curl -sf localhost:8000/health` | `"status":"healthy"` 포함 |
| 5 | Pushgateway eval 데이터 | `curl -sf localhost:9091/metrics` | `eval_accuracy` 문자열 포함 |
| 6 | nginx | HTTP 상태코드 | `200` 응답 |

**출력 형태:**
```
[✓] 컨테이너 (9/9 Up)
[✓] Grafana healthy
[✓] LLM-server healthy
[✓] RAG-server healthy
[✓] Pushgateway eval 데이터 존재
[✓] nginx 응답 200

모든 점검 통과 — 데모 준비 완료!
```

실패 시:
```
[✗] Pushgateway eval 데이터 없음 → eval_runner.py를 먼저 실행하세요
```

---

## 섹션 3: demo.sh 설계

### ~/.demo.env 형식
```bash
EC2_HOST=3.35.203.154
EC2_PEM=~/Downloads/yonam-test.pem
LLM_API_KEY=sk-...
```

### demo.sh 실행 흐름

**--preflight 모드:**
1. `~/.demo.env` 로드
2. SSH로 EC2 접속 → `verify.sh` 실행
3. 결과 출력 후 종료

**--run 모드:**
1. `~/.demo.env` 로드
2. SSH → `verify.sh` (pre-flight 점검)
3. PASS 시 SSH → eval_runner.py 실행 (진행 상황 실시간 출력)
4. 완료 후 `open "http://$EC2_HOST:3000/d/yeonam-overview"`
5. 패널 탐색 안내 출력:

```
=== 대시보드 탐색 순서 ===
1. [LLM vs RAG 정확도 비교] — RAG 정확도가 더 높은지 확인
2. [LLM-as-Judge 점수]     — RAG 품질 점수 비교
3. [토큰 사용량]            — RAG가 더 많은 토큰을 사용하지만
4. [컨테이너 CPU/메모리]    — 실제 리소스 소비 확인
5. [테스트 커버리지]        — CI 파이프라인 연동 확인
```

---

## 섹션 4: 데모 시나리오 흐름

### D-5분 (면접 직전)
```bash
./scripts/demo.sh --preflight
# → 모든 [✓] 확인 후 브라우저에 http://<EC2_HOST>:3000 열어두기
```

### 면접 중 라이브 시연 (약 3분)
1. **브라우저 Grafana 열기** — "이게 현재 운영 중인 관측성 대시보드입니다"
2. **패널 설명** — 기존 eval 결과 데이터로 각 패널 의미 설명 (1분)
3. **라이브 eval 실행:**
   ```bash
   ./scripts/demo.sh --run
   ```
   "지금 LLM과 RAG 파이프라인을 동시에 호출하고 있습니다. 결과가 Grafana에 실시간으로 반영됩니다."
4. **완료 후 브라우저 갱신** — 패널 숫자가 갱신된 것 확인

### 셀프서브 데모 (포트폴리오 링크)
- `http://<EC2_HOST>:3000/d/yeonam-overview` 를 README에 링크
- Grafana Anonymous Viewer 모드로 로그인 없이 접근 가능
- EC2 보안 그룹 TCP 3000 인바운드 오픈 필수

---

## 사전 조건 (구현 전 수동 작업)

1. **EC2 보안 그룹**: TCP 3000 인바운드 규칙 추가 (AWS 콘솔)
2. **`~/.demo.env` 파일 생성**: 위 형식대로 로컬에 생성
3. **초기 eval 데이터**: `demo.sh --run`을 한 번 실행해 Pushgateway에 초기 메트릭 적재
