# Single-Instance Docker Compose 통합 설계

**날짜:** 2026-06-13
**목표:** AWS EC2 t3.small 단일 인스턴스에서 nginx(frontend) + backend + ai-server(RAG 포함) 3개 컨테이너를 docker-compose로 운영해 비용을 최적화한다.
**베이스 프로젝트:** `RAG-Combination`

---

## 1. 배경 및 목표

### 기존 문제
- `web-service`의 `docker-compose.prod.yml`은 nginx + backend + llm-server + rag-server 4개 컨테이너를 실행한다.
- `rag-server` 이미지에 `faiss-cpu` + `sentence-transformers`(모델 포함) 가 포함되어 ~1.2GB를 차지한다.
- t3.small(2GB RAM) 위에서 4개 컨테이너 동시 실행 시 메모리 여유가 없다.

### 해결 방향
- `RAG-Combination`의 `llm_server`에는 이미 `HashEmbedding + FaissChunkStore` 기반 경량 RAG가 내장되어 있다 (`llm_client.py`).
- `rag-server` 컨테이너를 제거하고 `ai-server` 단일 컨테이너로 통합한다.
- RAG 기능은 런타임 API(`/admin/rag/toggle`)로 재시작 없이 on/off 가능하게 한다.
- MinIO를 제거하고 AWS S3 + EC2 IAM Role로 스토리지를 교체한다.

---

## 2. 아키텍처

```
[ Internet (Port 80) ]
        │
        ▼
┌─────────────────────────────────────────────┐  EC2 t3.small
│  nginx:stable-alpine (~20MB)                │
│  - /            → frontend/dist 정적 서빙    │
│  - /api/        → backend:8080 프록시        │
└──────────┬───────────────────────────────────┘
           │ Docker Bridge Network
     ┌─────▼──────┐       ┌─────────────────────────┐
     │  backend   │──────►│  ai-server (FastAPI)     │
     │ Spring Boot│       │  llm_client.py           │
     │  :8080     │       │  ┌───────────────────┐   │
     └────┬───────┘       │  │ HashEmbedding     │   │
          │               │  │ FaissChunkStore   │   │ ← RAG OFF 시 스킵
          │               │  │ rag_chunks.jsonl  │   │
          │               │  └───────────────────┘   │
          │               │  POST /admin/rag/toggle   │
          │               │  GET  /admin/rag/status   │
          │               └────────────┬────────────┘
          │                            │
          └──────────┬─────────────────┘
                     ▼
              [ AWS S3 + Bedrock ]
         (EC2 IAM Instance Profile 자격증명)
```

### 컨테이너별 예상 메모리
| 컨테이너 | 예상 RSS |
|---|---|
| nginx | ~10MB |
| backend (JVM) | ~300MB |
| ai-server (RAG OFF) | ~150MB |
| ai-server (RAG ON) | ~250MB |
| **합계** | **~460~560MB / 2GB** |

---

## 3. 파일 변경 목록

| 파일 | 작업 | 설명 |
|---|---|---|
| `docker-compose.yml` | 전면 재작성 | nginx + backend + ai-server 3-서비스 구성 |
| `backend/Dockerfile` | 신규 추가 | web-service에서 이식 (Maven 멀티스테이지 빌드) |
| `llm_server/Dockerfile` | 신규 추가 | python:3.10-slim 기반, sentence-transformers 제외 |
| `nginx/nginx.conf` | 신규 추가 | /api/ → backend, / → frontend/dist 정적 서빙 |
| `llm_server/main.py` | 수정 | `/admin/rag/toggle`, `/admin/rag/status` 엔드포인트 추가 |
| `llm_server/llm_client.py` | 수정 | `rag_enabled` 파라미터 추가, call_llm() 분기 수정 |
| `.env.example` | 수정 | MinIO 관련 변수 제거, AWS S3 변수 정리 |

---

## 4. docker-compose.yml 설계

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

**설계 결정:**
- MinIO 서비스 완전 제거. AWS S3 사용 시 `S3_ENDPOINT_URL`을 비워두면 boto3가 자동 전환.
- `rag_chunks.jsonl`을 read-only 볼륨 마운트 → 재빌드 없이 EC2에서 KB 파일만 교체 가능.
- `/admin/` 경로는 nginx에서 외부 노출하지 않고 Docker 내부 네트워크에서만 접근.

---

## 5. RAG 런타임 토글 API 설계

### 전역 상태 (`llm_server/main.py`)

```python
# 시작 시 환경변수로 초기값 설정
rag_enabled: bool = os.getenv("ENABLE_RAG", "true").lower() == "true"
```

### 엔드포인트

**`POST /admin/rag/toggle`**
- Request body: `{ "enabled": true }` 또는 `{ "enabled": false }`
- Response: `{ "rag_enabled": false, "message": "RAG disabled" }`
- 동작: 전역 `rag_enabled` 플래그를 즉시 변경. 진행 중인 job은 완료 후 다음 job부터 적용.

**`GET /admin/rag/status`**
- Response: `{ "rag_enabled": true, "queue_size": 0, "mock_llm": false }`

### `call_llm()` 분기 수정 (`llm_server/llm_client.py`)

```python
async def call_llm(document_text, perspectives, custom_prompt,
                   llm_api_key, rag_enabled=True):
    if mock_llm:
        if rag_enabled and document_text.strip():
            # 기존 HashEmbedding → FaissChunkStore → mock_generate 파이프라인
            init_chunk_store()
            if global_chunk_store:
                requirements = extract_requirements(document_text)
                evidence_map = retrieve_evidences(global_chunk_store, requirements, top_k=3)
                return json.dumps(mock_generate(requirements, evidence_map, ...), ...)
        # RAG OFF 또는 문서 없음: 정적 MOCK_RESPONSE 반환
        return json.dumps(MOCK_RESPONSE, ...)

    # 실 LLM 호출
    if rag_enabled and document_text.strip():
        # KB 로딩 + 요구사항 추출 + 증거 검색으로 system_prompt 보강
        atlassian_guide = load_atlassian_knowledge()
        # ... 보강된 프롬프트로 LLM 호출
    else:
        # document_text만으로 직접 LLM 호출 (RAG 컨텍스트 없음)
        pass
```

### 보안
- `/admin/` 경로는 nginx `location` 블록에서 노출하지 않는다.
- backend 컨테이너만 Docker 내부 네트워크를 통해 `http://ai-server:8000/admin/rag/toggle` 호출 가능.
- 외부 인터넷에서 직접 접근 불가.

---

## 6. Dockerfile 설계

### `backend/Dockerfile`
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

### `llm_server/Dockerfile`
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

**핵심:** `web-service/rag_server/Dockerfile`과 달리 `sentence-transformers` 모델 사전 다운로드 줄이 없음 → 빌드 시 ~500MB 절감.

---

## 7. nginx 설정

```nginx
server {
    listen 80;
    server_name _;
    client_max_body_size 20M;

    # Frontend SPA
    location / {
        root /usr/share/nginx/html;
        index index.html;
        try_files $uri $uri/ /index.html;
        location ~* \.(css|js|ico|svg|woff2|png|jpg|jpeg|gif)$ {
            expires 7d;
            add_header Cache-Control "public, immutable";
        }
    }

    # Backend API
    location /api/ {
        proxy_pass http://backend:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 120s;
    }

    # /admin/ 경로는 외부 노출 차단 (ai-server 내부 전용)
    location /admin/ {
        deny all;
        return 403;
    }
}
```

---

## 8. 환경변수 (.env)

```bash
# AWS (IAM Role 사용 시 ACCESS_KEY/SECRET_KEY 불필요)
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=yeonam-bucket

# AI Server
LLM_MODEL=gpt-4o-mini
MOCK_LLM=false
ENABLE_RAG=true          # 시작 시 RAG 초기 상태

# Backend → AI Server URL
AI_SERVER_URL=http://ai-server:8000
```

---

## 9. frontend dist 준비 전략

EC2 배포 전 로컬에서 빌드 후 `dist/`를 포함해 배포한다.

```bash
# 로컬
cd frontend
npm install
npm run build       # → frontend/dist/ 생성

# EC2 배포 시 (git에 dist 커밋하거나 scp로 전송)
scp -r frontend/dist ubuntu@<EC2_IP>:~/app/frontend/dist
```

nginx 컨테이너가 `./frontend/dist`를 볼륨 마운트로 서빙하므로 nginx 재시작 불필요.

---

## 10. 이미지 크기 비교

| 구성 | 이전 (web-service prod) | 이후 (이번 설계) |
|---|---|---|
| nginx | ~20MB | ~20MB |
| backend | ~200MB | ~200MB |
| llm-server | ~300MB | ~300MB |
| rag-server | ~1.2GB | **제거** |
| **합계** | **~1.72GB** | **~520MB** |
| **절감** | | **~1.2GB (70%)** |
