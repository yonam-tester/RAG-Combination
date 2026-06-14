---
name: run
description: Use when asked to run, start, launch, or restart the RAG-Combination project (yeonam-tester). Covers stopping conflicting processes on ports 8080/8000/3000/9000 and starting all four services in order.
---

# Run RAG-Combination Project

## Overview

4개 서비스를 순서대로 기동하는 프로젝트 런 스킬. 포트 충돌이 잦으므로 항상 기존 프로세스를 종료한 뒤 시작한다.

## Services & Ports

| 서비스 | 포트 | 기술 |
|--------|------|------|
| MinIO | 9000, 9001 | Docker |
| Backend | 8080 | Spring Boot (Maven) |
| RAG Server | 8000 | FastAPI + uvicorn |
| Frontend | 3000 | Vite (React) |

## Step 1 — Stop Conflicting Processes

```bash
# 관련 포트의 프로세스를 모두 종료 (없으면 무시)
for port in 8080 8000 3000 9001; do
  lsof -ti :$port 2>/dev/null | while read pid; do
    echo "Killing PID $pid on port $port"
    kill -9 $pid 2>/dev/null
  done
done
```

> **주의:** 포트 9000(MinIO API)과 9001(MinIO Console)은 Docker 실행 전 반드시 비워야 한다.

## Step 2 — Start MinIO (Docker)

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker-compose up -d
```

확인:
```bash
docker ps --filter name=yeonam-minio --format "{{.Status}}"
# "Up X seconds" 출력되면 정상
```

## Step 3 — Start Backend (Spring Boot)

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/backend
mvn spring-boot:run > /tmp/backend.log 2>&1 &
```

기동 확인 (최대 30초 대기):
```bash
until curl -s http://localhost:8080/actuator/health > /dev/null 2>&1 || \
      grep -q "Started TesterApplication" /tmp/backend.log 2>/dev/null; do
  sleep 2; echo "Waiting for backend..."
done
echo "Backend ready"
```

> `.env` 파일(`backend/.env`)에서 `AI_SERVER_URL=http://localhost:8000` 자동 로드됨.

## Step 4 — Start RAG Server (FastAPI)

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/rag_server
source venv/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8000 > /tmp/rag_server.log 2>&1 &
```

확인:
```bash
sleep 3 && curl -s http://localhost:8000/health | python3 -m json.tool
# {"status": "healthy", ...} 출력되면 정상
```

> `rag_server/.env`에서 `MOCK_RAG`, `MOCK_LLM` 환경변수 로드됨.

## Step 5 — Start Frontend (Vite)

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend
npm run dev > /tmp/frontend.log 2>&1 &
sleep 3 && grep "Local:" /tmp/frontend.log
```

정상 출력:
```
➜  Local:   http://localhost:3000/
```

## Quick Status Check

```bash
echo "=== Port Status ==="
for port in 9000 8080 8000 3000; do
  pid=$(lsof -ti :$port 2>/dev/null | head -1)
  [ -n "$pid" ] && echo "✓ :$port (PID $pid)" || echo "✗ :$port NOT running"
done
```

## Common Mistakes

| 상황 | 원인 | 해결 |
|------|------|------|
| `/api/*` → 401 Unauthorized | 포트 8080에 다른 Spring Boot 앱(Spring Security 포함) 점유 | Step 1에서 해당 PID kill |
| RAG server ImportError | venv 미활성화 상태에서 uvicorn 실행 | `source venv/bin/activate` 먼저 |
| Frontend 기동 불가 | 포트 3000 점유 | `lsof -ti :3000 | xargs kill -9` |
| Backend H2 DB 오류 | 이전 인스턴스가 DB 파일 lock 중 | 8080 프로세스 완전 종료 후 재시작 |

## Logs

```bash
tail -f /tmp/backend.log       # Spring Boot 로그
tail -f /tmp/rag_server.log    # FastAPI 로그
tail -f /tmp/frontend.log      # Vite 로그
```
