# Single-Instance Docker Compose 통합 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RAG-Combination 프로젝트를 AWS EC2 t3.small 단일 인스턴스에서 nginx(frontend) + backend + ai-server(RAG 포함) 3개 컨테이너로 운영할 수 있도록 Dockerfile, nginx 설정, docker-compose, RAG 런타임 토글 API를 구현한다.

**Architecture:** llm_server의 기존 HashEmbedding 기반 경량 RAG를 유지하면서 rag-server 컨테이너를 제거한다. RAG on/off는 `/admin/rag/toggle` API로 재시작 없이 제어한다. MinIO를 제거하고 AWS S3 + EC2 IAM Role로 스토리지를 교체한다.

**Tech Stack:** Python 3.10 / FastAPI / uvicorn / Spring Boot 17 / Maven / nginx:stable-alpine / Docker Compose 3.8 / pytest

---

## 파일 구조 (변경 대상)

```
RAG-Combination/
├── docker-compose.yml          ← 전면 재작성
├── .env.example                ← 신규 추가 (루트)
├── nginx/
│   └── nginx.conf              ← 신규 생성
├── backend/
│   ├── Dockerfile              ← 신규 생성
│   └── src/main/resources/
│       └── application.yml     ← S3 설정 환경변수화
├── llm_server/
│   ├── Dockerfile              ← 신규 생성
│   ├── .env.example            ← MinIO 제거, ENABLE_RAG 추가
│   ├── main.py                 ← rag_enabled 전역변수 + 토글 API 추가
│   ├── llm_client.py           ← call_llm()에 rag_enabled 파라미터 추가
│   └── tests/
│       ├── __init__.py         ← 신규 생성
│       ├── conftest.py         ← 신규 생성
│       ├── test_llm_client.py  ← 신규 생성
│       └── test_rag_toggle.py  ← 신규 생성
└── frontend/
    └── dist/                   ← npm run build 산출물 (nginx 마운트)
```

---

### Task 1: llm_server/Dockerfile 생성

**Files:**
- Create: `llm_server/Dockerfile`

- [ ] **Step 1: Dockerfile 작성**

`llm_server/Dockerfile`:
```dockerfile
FROM python:3.10-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 2: 빌드 검증**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker build -t yeonam-ai-test ./llm_server
```
Expected: `Successfully built` 또는 `naming to docker.io/library/yeonam-ai-test` 출력

- [ ] **Step 3: 이미지 크기 확인**

```bash
docker images yeonam-ai-test --format "{{.Size}}"
```
Expected: 400MB 이하 (sentence-transformers 없이 약 300MB)

- [ ] **Step 4: 임시 이미지 정리**

```bash
docker rmi yeonam-ai-test
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
git add llm_server/Dockerfile
git commit -m "feat: add llm_server Dockerfile (python:3.10-slim, no sentence-transformers)"
```

---

### Task 2: backend/Dockerfile 생성

**Files:**
- Create: `backend/Dockerfile`

- [ ] **Step 1: Dockerfile 작성 (Maven 멀티스테이지 빌드)**

`backend/Dockerfile`:
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN mkdir -p /app/data && \
    useradd -u 1001 appuser && \
    chown -R appuser:appuser /app
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: 빌드 검증 (3~5분 소요)**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker build -t yeonam-backend-test ./backend
```
Expected: `Successfully built` 출력

- [ ] **Step 3: 임시 이미지 정리**

```bash
docker rmi yeonam-backend-test
```

- [ ] **Step 4: 커밋**

```bash
git add backend/Dockerfile
git commit -m "feat: add backend Dockerfile (Maven multi-stage, eclipse-temurin:17-jre)"
```

---

### Task 3: nginx/nginx.conf 생성

**Files:**
- Create: `nginx/nginx.conf`

- [ ] **Step 1: nginx 디렉토리 생성**

```bash
mkdir -p /Users/rinaeshin/IdeaProjects/RAG-Combination/nginx
```

- [ ] **Step 2: nginx.conf 작성**

`nginx/nginx.conf`:
```nginx
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    keepalive_timeout 65;
    client_max_body_size 20M;

    server {
        listen 80;
        server_name _;

        # Frontend SPA
        location / {
            root /usr/share/nginx/html;
            index index.html;
            try_files $uri $uri/ /index.html;

            location ~* \.(css|js|ico|svg|woff2|png|jpg|jpeg|gif)$ {
                expires 7d;
                add_header Cache-Control "public, immutable";
                access_log off;
            }
        }

        # Backend API
        location /api/ {
            proxy_pass http://backend:8080/api/;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_read_timeout 120s;
        }

        # /admin/ 외부 차단 — ai-server 내부 전용
        location /admin/ {
            deny all;
            return 403;
        }
    }
}
```

- [ ] **Step 3: nginx 문법 검증**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker run --rm -v "$(pwd)/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:stable-alpine nginx -t
```
Expected: `nginx: configuration file /etc/nginx/nginx.conf test is successful`

- [ ] **Step 4: 커밋**

```bash
git add nginx/nginx.conf
git commit -m "feat: add nginx config with SPA serving, /api/ proxy, /admin/ blocked externally"
```

---

### Task 4: llm_client.py — call_llm에 rag_enabled 파라미터 추가 (TDD)

**Files:**
- Modify: `llm_server/llm_client.py`
- Create: `llm_server/tests/__init__.py`
- Create: `llm_server/tests/conftest.py`
- Create: `llm_server/tests/test_llm_client.py`

- [ ] **Step 1: tests 디렉토리 및 conftest 생성**

`llm_server/tests/__init__.py` (빈 파일):
```python
```

`llm_server/tests/conftest.py`:
```python
import sys
import os

# llm_server/ 디렉토리를 import 경로에 추가
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
```

- [ ] **Step 2: 실패하는 테스트 작성**

`llm_server/tests/test_llm_client.py`:
```python
import asyncio
import json
import os

os.environ.setdefault("MOCK_LLM", "true")

from llm_client import call_llm, MOCK_RESPONSE


def test_call_llm_rag_disabled_returns_mock_response():
    """RAG OFF 시 document_text가 있어도 정적 MOCK_RESPONSE 반환"""
    result = asyncio.run(
        call_llm(
            document_text="FR-01: 사용자는 유효한 이메일로 로그인할 수 있다.",
            perspectives=[],
            custom_prompt="",
            llm_api_key=None,
            rag_enabled=False,
        )
    )
    data = json.loads(result)
    assert data == MOCK_RESPONSE


def test_call_llm_rag_enabled_empty_doc_returns_mock_response():
    """RAG ON이더라도 document_text가 비어있으면 MOCK_RESPONSE 반환"""
    result = asyncio.run(
        call_llm(
            document_text="",
            perspectives=[],
            custom_prompt="",
            llm_api_key=None,
            rag_enabled=True,
        )
    )
    data = json.loads(result)
    assert data == MOCK_RESPONSE


def test_call_llm_rag_enabled_with_doc_returns_test_cases():
    """RAG ON + document_text 있으면 동적 testCases 반환"""
    result = asyncio.run(
        call_llm(
            document_text="FR-01: 사용자는 유효한 이메일로 로그인할 수 있다.\nFR-02: 비밀번호는 8자 이상이어야 한다.",
            perspectives=["보안"],
            custom_prompt="",
            llm_api_key=None,
            rag_enabled=True,
        )
    )
    data = json.loads(result)
    assert "testCases" in data
    assert len(data["testCases"]) > 0
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/llm_server
pip install pytest -q
pytest tests/test_llm_client.py -v
```
Expected: `TypeError: call_llm() got an unexpected keyword argument 'rag_enabled'`

- [ ] **Step 4: llm_client.py — call_llm 시그니처에 rag_enabled 추가**

`llm_server/llm_client.py` 329번째 줄 변경:

Before:
```python
async def call_llm(document_text: str, perspectives: list, custom_prompt: str, llm_api_key: str = None) -> str:
```

After:
```python
async def call_llm(document_text: str, perspectives: list, custom_prompt: str, llm_api_key: str = None, rag_enabled: bool = True) -> str:
```

- [ ] **Step 5: llm_client.py — MOCK 경로 분기에 rag_enabled 조건 추가**

`llm_server/llm_client.py` 338번째 줄 변경:

Before:
```python
        if document_text and document_text.strip():
            init_chunk_store()
```

After:
```python
        if rag_enabled and document_text and document_text.strip():
            init_chunk_store()
```

- [ ] **Step 6: llm_client.py — 실 LLM 경로에 rag_enabled 분기 추가**

`llm_server/llm_client.py` 353~362번째 줄 변경:

Before:
```python
    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    logger.info(f"Calling real LLM model: {model}")
    
    atlassian_guide = load_atlassian_knowledge()
    
    system_prompt = (
        "당신은 소프트웨어 테스트 및 QA 엔지니어입니다. 입력된 소프트웨어 요구사항 명세서 본문을 바탕으로 TDD/QA 검증을 위한 테스트 케이스 목록을 생성하시오.\n\n"
        "분석 시 반드시 아래의 Atlassian QA 설계 원칙 지식을 준수하고 참고하여 분석하십시오:\n"
        f"{atlassian_guide}\n\n"
```

After:
```python
    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    logger.info(f"Calling real LLM model: {model}")

    kb_section = ""
    if rag_enabled:
        atlassian_guide = load_atlassian_knowledge()
        kb_section = (
            "분석 시 반드시 아래의 Atlassian QA 설계 원칙 지식을 준수하고 참고하여 분석하십시오:\n"
            f"{atlassian_guide}\n\n"
        )

    system_prompt = (
        "당신은 소프트웨어 테스트 및 QA 엔지니어입니다. 입력된 소프트웨어 요구사항 명세서 본문을 바탕으로 TDD/QA 검증을 위한 테스트 케이스 목록을 생성하시오.\n\n"
        f"{kb_section}"
```

- [ ] **Step 7: 테스트 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/llm_server
pytest tests/test_llm_client.py -v
```
Expected: `3 passed`

- [ ] **Step 8: 커밋**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
git add llm_server/llm_client.py llm_server/tests/
git commit -m "feat: add rag_enabled param to call_llm(), skip RAG pipeline and KB loading when disabled"
```

---

### Task 5: main.py — 전역 RAG 플래그 + 토글 API 추가 (TDD)

**Files:**
- Modify: `llm_server/main.py`
- Create: `llm_server/tests/test_rag_toggle.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`llm_server/tests/test_rag_toggle.py`:
```python
import os

os.environ.setdefault("MOCK_LLM", "true")
os.environ.setdefault("ENABLE_RAG", "true")

import pytest
from fastapi.testclient import TestClient
import main as main_module
from main import app


@pytest.fixture(autouse=True)
def reset_rag_state():
    """각 테스트 전에 rag_enabled를 True로 초기화"""
    main_module.rag_enabled = True
    yield


def test_rag_status_default_true():
    with TestClient(app) as client:
        response = client.get("/admin/rag/status")
    assert response.status_code == 200
    assert response.json()["rag_enabled"] is True


def test_rag_toggle_disable():
    with TestClient(app) as client:
        response = client.post("/admin/rag/toggle", json={"enabled": False})
    assert response.status_code == 200
    data = response.json()
    assert data["rag_enabled"] is False
    assert "disabled" in data["message"]


def test_rag_toggle_enable():
    main_module.rag_enabled = False
    with TestClient(app) as client:
        response = client.post("/admin/rag/toggle", json={"enabled": True})
    assert response.status_code == 200
    data = response.json()
    assert data["rag_enabled"] is True
    assert "enabled" in data["message"]


def test_rag_status_reflects_toggle():
    with TestClient(app) as client:
        client.post("/admin/rag/toggle", json={"enabled": False})
        response = client.get("/admin/rag/status")
    assert response.json()["rag_enabled"] is False


def test_health_includes_rag_enabled():
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    assert "rag_enabled" in response.json()
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/llm_server
pytest tests/test_rag_toggle.py -v
```
Expected: `AttributeError: module 'main' has no attribute 'rag_enabled'` 또는 `404 Not Found`

- [ ] **Step 3: main.py — 전역 rag_enabled 변수 추가**

`llm_server/main.py` 16번째 줄 (`load_dotenv()` 바로 뒤) 에 추가:

Before:
```python
# Load environment variables
load_dotenv()

# Configure Logging
```

After:
```python
# Load environment variables
load_dotenv()

# RAG runtime state — modified via POST /admin/rag/toggle without restart
rag_enabled: bool = os.getenv("ENABLE_RAG", "true").lower() == "true"

# Configure Logging
```

- [ ] **Step 4: main.py — process_job에서 rag_enabled 전달**

`llm_server/main.py` 45번째 줄 변경:

Before:
```python
        raw_response = await call_llm(combined_text, perspectives, custom_prompt, llm_api_key)
```

After:
```python
        raw_response = await call_llm(combined_text, perspectives, custom_prompt, llm_api_key, rag_enabled)
```

- [ ] **Step 5: main.py — RagToggleRequest 모델과 토글 엔드포인트 추가**

`llm_server/main.py` 82번째 줄 (`from pydantic import BaseModel` 바로 뒤) 에 추가:

Before:
```python
from pydantic import BaseModel
from typing import List, Optional

class TriggerRequest(BaseModel):
```

After:
```python
from pydantic import BaseModel
from typing import List, Optional


class RagToggleRequest(BaseModel):
    enabled: bool


@app.post("/admin/rag/toggle")
async def toggle_rag(req: RagToggleRequest):
    global rag_enabled
    rag_enabled = req.enabled
    status = "enabled" if rag_enabled else "disabled"
    logger.info(f"RAG toggled to: {status}")
    return {"rag_enabled": rag_enabled, "message": f"RAG {status}"}


@app.get("/admin/rag/status")
async def rag_status():
    return {
        "rag_enabled": rag_enabled,
        "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
        "queue_size": queue_manager.queue.qsize(),
    }


class TriggerRequest(BaseModel):
```

- [ ] **Step 6: main.py — health_check에 rag_enabled 추가**

`llm_server/main.py` 마지막 `health_check` 함수 변경:

Before:
```python
@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
        "queue_size": queue_manager.queue.qsize()
    }
```

After:
```python
@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "mock_llm": os.getenv("MOCK_LLM", "true").lower() == "true",
        "rag_enabled": rag_enabled,
        "queue_size": queue_manager.queue.qsize(),
    }
```

- [ ] **Step 7: 전체 테스트 통과 확인**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/llm_server
pytest tests/ -v
```
Expected: `8 passed` (test_llm_client 3개 + test_rag_toggle 5개)

- [ ] **Step 8: 커밋**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
git add llm_server/main.py llm_server/tests/test_rag_toggle.py
git commit -m "feat: add /admin/rag/toggle and /admin/rag/status endpoints with global rag_enabled flag"
```

---

### Task 6: docker-compose.yml 전면 재작성

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: docker-compose.yml 재작성**

`docker-compose.yml`:
```yaml
version: '3.8'

services:
  nginx:
    image: nginx:stable-alpine
    container_name: yeonam-nginx
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./frontend/dist:/usr/share/nginx/html:ro
    depends_on:
      - backend
      - ai-server
    networks:
      - yeonam-network
    restart: always

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: yeonam-backend
    expose:
      - "8080"
    env_file:
      - .env
    volumes:
      - backend_data:/app/data
    depends_on:
      - ai-server
    networks:
      - yeonam-network
    restart: always

  ai-server:
    build:
      context: ./llm_server
      dockerfile: Dockerfile
    container_name: yeonam-ai
    expose:
      - "8000"
    env_file:
      - .env
    volumes:
      - ./llm_server/rag_chunks.jsonl:/app/rag_chunks.jsonl:ro
    networks:
      - yeonam-network
    restart: always

  # HTTPS 인증서 갱신 (선택적 — docker compose --profile https up)
  certbot:
    profiles:
      - https
    image: certbot/certbot
    container_name: yeonam-certbot
    volumes:
      - ./nginx/certbot/conf:/etc/letsencrypt
      - ./nginx/certbot/www:/var/www/certbot
    entrypoint: "/bin/sh -c 'trap exit TERM; while :; do certbot renew --quiet; sleep 12h & wait $${!}; done;'"

networks:
  yeonam-network:
    driver: bridge

volumes:
  backend_data:
```

- [ ] **Step 2: docker-compose 설정 문법 검증**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker compose config --quiet
```
Expected: 오류 없이 exit code 0

- [ ] **Step 3: 커밋**

```bash
git add docker-compose.yml
git commit -m "feat: rewrite docker-compose — 3-service stack (nginx+backend+ai-server), remove MinIO and rag-server"
```

---

### Task 7: 환경변수 파일 및 backend application.yml 업데이트

**Files:**
- Modify: `llm_server/.env.example`
- Modify: `backend/.env.example`
- Create: `.env.example` (루트)
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: llm_server/.env.example 업데이트**

`llm_server/.env.example`:
```bash
# AI Server 동작 모드
MOCK_LLM=false
ENABLE_RAG=true
LLM_MODEL=gpt-4o-mini

# Webhook (Docker 내부 네트워크 주소)
BACKEND_URL=http://backend:8080

# AWS S3 (EC2 IAM Role 사용 시 ACCESS_KEY/SECRET_KEY 불필요)
AWS_REGION=ap-northeast-2
S3_BUCKET=yeonam-documents
# S3_ENDPOINT_URL=    ← 비워두면 실제 AWS S3 사용

# 선택: LLM API Key (사용자 입력으로 동적 주입도 가능)
# OPENAI_API_KEY=sk-...
```

- [ ] **Step 2: backend/.env.example 업데이트**

`backend/.env.example`:
```bash
# AI Server URL (Docker 내부 네트워크)
AI_SERVER_URL=http://ai-server:8000

# AWS 설정 (EC2 IAM Role 사용 시 ACCESS_KEY/SECRET_KEY 불필요)
AWS_REGION=ap-northeast-2
S3_BUCKET=yeonam-documents
S3_REPORT_BUCKET=yeonam-reports
# S3_ENDPOINT_URL=    ← 비워두면 실제 AWS S3 사용 (MinIO: http://minio:9000)
AWS_BEDROCK_MODEL_ID=anthropic.claude-3-5-haiku-20241022
```

- [ ] **Step 3: 루트 .env.example 생성 (docker-compose용 통합 파일)**

`.env.example` (프로젝트 루트):
```bash
# ========== AI Server ==========
MOCK_LLM=false
ENABLE_RAG=true
LLM_MODEL=gpt-4o-mini
BACKEND_URL=http://backend:8080

# ========== Backend ==========
AI_SERVER_URL=http://ai-server:8000
AWS_BEDROCK_MODEL_ID=anthropic.claude-3-5-haiku-20241022

# ========== AWS (EC2 IAM Role 사용 시 키 불필요) ==========
AWS_REGION=ap-northeast-2
S3_BUCKET=yeonam-documents
S3_REPORT_BUCKET=yeonam-reports
# S3_ENDPOINT_URL=    ← 비워두면 AWS S3, MinIO 사용 시 http://minio:9000
```

- [ ] **Step 4: backend/src/main/resources/application.yml — S3 설정 환경변수화**

`backend/src/main/resources/application.yml` 에서 aws.s3 섹션 변경:

Before:
```yaml
aws:
  s3:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    region: us-east-1
    buckets:
      documents: yeonam-documents
      reports: yeonam-reports
  region: ${AWS_REGION:us-east-1}
  bedrock:
    model-id: ${AWS_BEDROCK_MODEL_ID:anthropic.claude-3-haiku-20240307-v1:0}
```

After:
```yaml
aws:
  s3:
    endpoint: ${S3_ENDPOINT_URL:}
    access-key: ${AWS_ACCESS_KEY_ID:}
    secret-key: ${AWS_SECRET_ACCESS_KEY:}
    region: ${AWS_REGION:ap-northeast-2}
    buckets:
      documents: ${S3_BUCKET:yeonam-documents}
      reports: ${S3_REPORT_BUCKET:yeonam-reports}
  region: ${AWS_REGION:ap-northeast-2}
  bedrock:
    model-id: ${AWS_BEDROCK_MODEL_ID:anthropic.claude-3-5-haiku-20241022}
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
git add llm_server/.env.example backend/.env.example .env.example
git add backend/src/main/resources/application.yml
git commit -m "chore: externalize S3/AWS config via env vars, remove MinIO hardcoding, add root .env.example"
```

---

### Task 8: frontend/dist 빌드

**Files:**
- Generate: `frontend/dist/` (빌드 산출물)

- [ ] **Step 1: 의존성 설치**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend
npm install
```
Expected: `node_modules/` 갱신, 오류 없음

- [ ] **Step 2: 프로덕션 빌드**

```bash
npm run build
```
Expected: `dist/` 디렉토리 생성 (`dist/index.html` 포함)

- [ ] **Step 3: 빌드 결과 확인**

```bash
ls /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend/dist/
```
Expected: `index.html` 과 `assets/` 디렉토리 존재

- [ ] **Step 4: .gitignore에서 dist 제외 여부 확인**

```bash
grep -r "dist" /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend/.gitignore 2>/dev/null || echo "dist not excluded"
```

- **dist가 .gitignore에 있는 경우:** EC2 배포 시 scp로 전송
  ```bash
  # EC2 배포 명령 (EC2_IP 치환 필요)
  scp -r frontend/dist ubuntu@<EC2_IP>:~/app/frontend/dist
  ```
- **dist가 .gitignore에 없는 경우:** git commit으로 포함
  ```bash
  git add frontend/dist
  git commit -m "build: add frontend dist for nginx static serving"
  ```

- [ ] **Step 5: vite.config.ts base 경로 확인**

```bash
cat /Users/rinaeshin/IdeaProjects/RAG-Combination/frontend/vite.config.ts
```
Expected: `base: '/'` 또는 base 설정 없음 (nginx 루트 서빙과 호환)
만약 `base: '/web-service/'` 처럼 서브패스가 있으면 `base: '/'`로 변경 후 재빌드

---

### Task 9: 로컬 통합 Smoke Test

**Files:**
- Create: `.env` (로컬 테스트용, git 제외)

- [ ] **Step 1: 로컬 테스트용 .env 생성**

```bash
cat > /Users/rinaeshin/IdeaProjects/RAG-Combination/.env << 'EOF'
MOCK_LLM=true
ENABLE_RAG=true
LLM_MODEL=gpt-4o-mini
BACKEND_URL=http://backend:8080
AI_SERVER_URL=http://ai-server:8000
AWS_REGION=ap-northeast-2
S3_BUCKET=yeonam-documents
S3_REPORT_BUCKET=yeonam-reports
AWS_BEDROCK_MODEL_ID=anthropic.claude-3-5-haiku-20241022
EOF
```

- [ ] **Step 2: .env를 .gitignore에 추가**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
echo ".env" >> .gitignore
git add .gitignore
git commit -m "chore: exclude .env from git"
```

- [ ] **Step 3: 전체 스택 빌드 및 기동**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
docker compose up --build -d
```
Expected: 3개 컨테이너 모두 `Up` 상태

- [ ] **Step 4: 컨테이너 상태 확인**

```bash
docker compose ps
```
Expected:
```
NAME             STATUS
yeonam-ai        Up ...
yeonam-backend   Up ...
yeonam-nginx     Up ...
```

- [ ] **Step 5: ai-server health check**

```bash
curl -s http://localhost:80/api/health 2>/dev/null; \
docker compose exec ai-server curl -s http://localhost:8000/health
```
Expected (ai-server):
```json
{"status":"healthy","mock_llm":true,"rag_enabled":true,"queue_size":0}
```

- [ ] **Step 6: RAG 비활성화 토글**

```bash
docker compose exec ai-server curl -s -X POST http://localhost:8000/admin/rag/toggle \
  -H "Content-Type: application/json" -d '{"enabled": false}'
```
Expected:
```json
{"rag_enabled":false,"message":"RAG disabled"}
```

- [ ] **Step 7: RAG 상태 확인**

```bash
docker compose exec ai-server curl -s http://localhost:8000/admin/rag/status
```
Expected:
```json
{"rag_enabled":false,"mock_llm":true,"queue_size":0}
```

- [ ] **Step 8: RAG 재활성화**

```bash
docker compose exec ai-server curl -s -X POST http://localhost:8000/admin/rag/toggle \
  -H "Content-Type: application/json" -d '{"enabled": true}'
```
Expected:
```json
{"rag_enabled":true,"message":"RAG enabled"}
```

- [ ] **Step 9: frontend 서빙 확인**

```bash
curl -s http://localhost:80/ | grep -c "<!DOCTYPE html>"
```
Expected: `1` (HTML 응답 확인)

- [ ] **Step 10: /admin/ 외부 차단 확인**

```bash
curl -o /dev/null -s -w "%{http_code}" http://localhost:80/admin/rag/status
```
Expected: `403`

- [ ] **Step 11: 스택 종료**

```bash
docker compose down
```

- [ ] **Step 12: 최종 커밋**

```bash
cd /Users/rinaeshin/IdeaProjects/RAG-Combination
git add -A
git status  # .env가 추적되지 않는지 확인
git commit -m "chore: finalize single-instance docker-compose integration"
```

---

## 셀프 리뷰 체크리스트

- [x] **Spec 커버리지**: 모든 스펙 섹션(Dockerfile, nginx, docker-compose, RAG 토글, env, frontend) 대응 태스크 존재
- [x] **Placeholder 없음**: TBD/TODO 없음, 모든 코드 블록 완성
- [x] **타입 일관성**: `rag_enabled: bool`, `call_llm(..., rag_enabled=True)`, `RagToggleRequest.enabled: bool` 일관
- [x] **함수명 일관성**: Task 4에서 추가한 `rag_enabled` 파라미터를 Task 5의 `process_job`에서 동일하게 참조
- [x] **보안**: Task 9 Step 10에서 `/admin/` 외부 차단 403 검증
