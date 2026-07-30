# 연암 테스터 (Yeonam Tester)

> AI 기반 TDD 검증 및 테스트 케이스 자동화 플랫폼 (MVP)

요구사항 명세서나 기획 설계 문서를 업로드하면 AI(LLM + RAG)가 명세 문맥을 분석하여 QA/TDD용 테스트 시나리오와 구체적인 테스트 케이스를 자동 생성합니다. 결과는 검증 보고서로 변환하여 다운로드할 수 있습니다.

---

## 목차

1. [시스템 아키텍처](#시스템-아키텍처)
2. [기술 스택](#기술-스택)
3. [사전 요구 사항](#사전-요구-사항)
4. [로컬 실행 가이드](#로컬-실행-가이드)
5. [환경 변수 설정](#환경-변수-설정)
6. [AI 서버 교체 방법](#ai-서버-교체-방법)
7. [RAG 지식베이스](#rag-지식베이스)
8. [API 엔드포인트 요약](#api-엔드포인트-요약)
9. [테스트 실행](#테스트-실행)
10. [모듈별 상세 가이드](#모듈별-상세-가이드)

---

## 시스템 아키텍처

비동기 웹훅 기반의 느슨하게 결합된 4-모듈 구조입니다.

```
┌─────────────────────────────────────────────────────────────────┐
│  React Frontend (:5173)                                         │
│  문서 업로드 / 분석 트리거 / 결과 시각화                           │
└───────────────────────┬─────────────────────────────────────────┘
                        │ REST API / Polling
┌───────────────────────▼─────────────────────────────────────────┐
│  Spring Boot Backend (:8080)                                    │
│  파일 관리 / 분석 오케스트레이션 / 웹훅 수신                        │
└──────────┬──────────────────────┬──────────────────────────────┘
           │ S3 API               │ Async Trigger / Webhook
   ┌───────▼──────┐    ┌──────────▼───────────────────────────┐
   │ MinIO (:9000)│    │  FastAPI AI Server (:8000)            │
   │ 원본 문서 /  │    │  ┌──────────────┐  ┌───────────────┐ │
   │ 보고서 저장  │    │  │  llm_server  │  │  rag_server   │ │
   └───────┬──────┘    │  │  (단순 LLM)  │  │ (LangChain +  │ │
           │           │  │              │  │   Qdrant RAG) │ │
           │           │  └──────────────┘  └───────┬───────┘ │
           └───────────│ Document Download           │         │
                       └────────────────────────────┼─────────┘
                                                     │ 벡터 검색
                                          ┌──────────▼───────────┐
                                          │  Qdrant (:6333)      │
                                          │  문서 청크 +         │
                                          │  QA 지식카드 (단일   │
                                          │  컬렉션, COSINE)     │
                                          └──────────────────────┘
```

**데이터 흐름:**
1. 프론트엔드에서 문서 업로드 및 분석 조건(QA 관점, API Key 등) 입력
2. 백엔드가 MinIO에 원본 파일 저장 후 AI 서버로 비동기 트리거 전송
3. AI 서버가 `asyncio.Queue`로 작업 수신 → S3 다운로드 → 분석 실행
4. 분석 완료 시 웹훅으로 백엔드에 결과 콜백
5. 프론트엔드 폴링으로 결과 수신 및 시각화

---

## 기술 스택

| 모듈 | 기술 |
|------|------|
| **Frontend** | React 18, TypeScript, Vite, TailwindCSS, React Router v6 |
| **Backend** | Java 17, Spring Boot 3.3.0, Spring Data JPA, H2 Database, AWS Java SDK |
| **RAG Server** | Python 3.10+, FastAPI, LangChain, Qdrant, HuggingFace Embeddings (`all-MiniLM-L6-v2`), LiteLLM SDK, PyMuPDF |
| **LLM Server** | Python 3.10+, FastAPI, LiteLLM SDK, pypdf, python-docx |
| **Storage** | MinIO (S3 호환), Qdrant (벡터 DB), H2 File Mode DB |

---

## 사전 요구 사항

| 도구 | 버전 |
|------|------|
| Docker Desktop | 최신 버전 |
| Java JDK | 17 이상 |
| Node.js | 18 이상 (npm 포함) |
| Python | 3.10 이상 |

---

## 로컬 실행 가이드

### Step 1. 인프라 컨테이너 실행 (MinIO + Qdrant)

AI 서버를 호스트에서 직접 띄울 경우, 의존 인프라만 골라 실행합니다.

```bash
docker compose up -d minio

# Qdrant는 compose에서 호스트 포트를 발행하지 않으므로(아래 주의 참고),
# 호스트에서 rag_server를 실행할 때는 포트를 발행한 단독 컨테이너를 씁니다.
docker run -d --name qdrant-dev -p 6333:6333 qdrant/qdrant:v1.18.0
```

> **주의 1.** `docker compose up -d`를 인자 없이 실행하면 nginx·backend·모니터링 스택까지 **10개 서비스 전체**가 뜹니다. 전체 스택을 컨테이너로 돌릴 때만 사용하세요.
>
> **주의 2.** compose의 `qdrant` 서비스는 `expose`만 선언해 컨테이너 네트워크 내부에서만 접근됩니다(호스트/외부 노출 없음). 전체 스택을 컨테이너로 띄우면 `rag-server`가 `http://qdrant:6333`으로 접근하므로 문제가 없지만, 호스트에서 `uvicorn`으로 실행할 때는 위처럼 포트를 발행한 컨테이너가 필요합니다.

| 엔드포인트 | 주소 | 용도 |
|------------|------|------|
| S3 API | `http://localhost:9000` | 백엔드 / AI 서버 연동 |
| Console UI | `http://localhost:9001` | MinIO 관리 콘솔 (계정: `minioadmin` / `minioadmin`) |
| Qdrant (개발용 단독 컨테이너) | `http://localhost:6333` | 벡터 DB. 대시보드: `/dashboard` |

---

### Step 2. AI 분석 서버 실행 (RAG Server 또는 LLM Server)

`rag_server` 또는 `llm_server` 중 하나를 선택하여 포트 8000으로 실행합니다. (두 서버는 동일 포트에서 교체 가능합니다. [AI 서버 교체 방법](#ai-서버-교체-방법) 참고)

#### 2-1. 가상환경 생성 및 의존성 설치

```bash
# rag_server 예시 (llm_server도 동일한 방법으로 실행)
cd rag_server

python -m venv venv

# macOS / Linux
source venv/bin/activate

# Windows PowerShell
.\venv\Scripts\Activate.ps1

pip install -r requirements.txt
```

#### 2-2. RAG 지식베이스 초기 구축 (rag_server 전용)

rag_server를 처음 실행할 때 한 번만 수행합니다.

```bash
# rag_server/ 디렉토리 내에서 실행
python embedded/generate_rag_chunks.py
```

`rag_server/knowledge_base/*.json`(QA 지식카드 원본)을 읽어 `rag_server/rag_chunks.jsonl`을 생성합니다. 서버 구동 시 이 파일이 자동으로 Qdrant에 적재됩니다(현재 **184청크 / 7종 출처**).

지식카드를 추가하거나 수정했다면 이 스크립트를 다시 실행해야 합니다. `rag_chunks.jsonl`이 `knowledge_base/`보다 오래되면 일부 출처가 검색에서 조용히 빠지며, 이를 감지하는 테스트가 `test_ingestion.py`에 있습니다.

#### 2-3. 환경 변수 설정

`.env.example`을 복사하여 `.env`를 생성합니다.

```bash
cp .env.example .env
```

**실제 LLM 연동 모드 (기본값):**

```env
MOCK_LLM=false
LLM_MODEL=gpt-4o-mini
BACKEND_URL=http://localhost:8080
QDRANT_URL=http://localhost:6333
QDRANT_COLLECTION=yeonam_knowledge
```

**로컬 Mock 모드 (API 비용 없음):**

```env
MOCK_LLM=true
BACKEND_URL=http://localhost:8080
QDRANT_URL=http://localhost:6333
```

> `MOCK_LLM`의 기본값은 `false`입니다. 환경변수 누락으로 가짜 결과가 정상 응답처럼 나가지 않도록, Mock은 반드시 명시적으로 켜야 합니다. Mock을 끈 상태에서 LLM 호출이나 응답 파싱이 실패하면 mock으로 폴백하지 않고 분석이 `FAILED`로 처리됩니다.

> 실제 LLM 모드에서는 프론트엔드 UI의 API Key 입력란에 유효한 OpenAI API Key를 입력해야 합니다.

#### 2-4. 서버 실행

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

### Step 3. 백엔드 서버 실행 (Spring Boot)

```bash
cd backend

# macOS / Linux
./mvnw spring-boot:run
# 또는 시스템 Maven 사용
mvn spring-boot:run

# Windows
.maven\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
```

| 엔드포인트 | 주소 |
|------------|------|
| API Base URL | `http://localhost:8080` |
| H2 Console | `http://localhost:8080/h2-console` |

H2 Console 접속 정보:
- JDBC URL: `jdbc:h2:file:./data/yeonam_db`
- 사용자 ID: `sa`
- 비밀번호: 없음

최초 실행 시 MinIO에 `yeonam-documents`, `yeonam-reports` 버킷이 없으면 자동 생성됩니다.

---

### Step 4. 프론트엔드 실행 (React)

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:3000`으로 접속합니다.

---

### 포트 요약

| 서비스 | 포트 | 설명 |
|--------|------|------|
| Frontend (React) | 3000 | Vite 개발 서버 |
| Backend (Spring Boot) | 8080 | REST API 서버 |
| AI Server (FastAPI) | 8000 | RAG Server 또는 LLM Server |
| Qdrant | 6333 | 벡터 DB (호스트 개발 시 단독 컨테이너로 발행) |
| MinIO S3 API | 9000 | 오브젝트 스토리지 API |
| MinIO Console | 9001 | 웹 관리 콘솔 |

---

## 환경 변수 설정

### rag_server / llm_server `.env`

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MOCK_LLM` | `false` | `true`면 LLM 호출을 Mock으로 처리 (API 비용 없음). 기본값이 `false`이므로 Mock은 명시적으로 켜야 합니다 |
| `BACKEND_URL` | `http://localhost:8080` | 웹훅 콜백을 보낼 백엔드 주소 |
| `LLM_MODEL` | `gpt-4o-mini` | 실제 LLM 호출 시 사용할 모델명 |
| `EMBEDDING_MODEL` | `all-MiniLM-L6-v2` | 로컬 임베딩 모델명 (384차원, rag_server 전용) |
| `QDRANT_URL` | `http://qdrant:6333` | Qdrant 주소. `:memory:`면 서버 없이 로컬 모드 (테스트용, rag_server 전용) |
| `QDRANT_COLLECTION` | `yeonam_knowledge` | 컬렉션명 (rag_server 전용) |

### backend `.env`

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AI_SERVER_URL` | `http://localhost:8000` | 연동할 AI 서버 주소 |
| `LLM_PROVIDER` | `openai` | LLM 프로바이더 (`openai` 또는 `bedrock`) |
| `LLM_API_KEY` | 없음 (**필수**) | OpenAI 호환 엔드포인트용 API 키 |
| `LLM_BASE_URL` | `https://api.openai.com/v1` | OpenAI 호환 엔드포인트 URL. 외부 프록시(LiteLLM Proxy 등)를 별도로 운영한다면 그 주소로 변경 가능 |
| `LLM_MODEL` | `gpt-4o` | 사용할 모델명 |
| `AWS_REGION` | `us-east-1` | Bedrock 사용 시 AWS 리전 |
| `AWS_BEDROCK_MODEL_ID` | `anthropic.claude-3-haiku-20240307-v1:0` | Bedrock 사용 시 모델 ID |

**설정 예시:**

```env
# OpenAI 직접 사용 (기본값)
LLM_PROVIDER=openai
LLM_API_KEY=sk-...
LLM_MODEL=gpt-4o

# 외부 OpenAI 호환 프록시 경유 (직접 띄운 LiteLLM Proxy 등)
# 주의: 이 프로젝트는 프록시 서버를 포함하지 않습니다. 별도로 운영해야 합니다.
LLM_PROVIDER=openai
LLM_BASE_URL=http://localhost:4000/v1
LLM_API_KEY=anything
LLM_MODEL=claude-3-5-sonnet-20241022

# AWS Bedrock
LLM_PROVIDER=bedrock
AWS_REGION=us-east-1
AWS_BEDROCK_MODEL_ID=anthropic.claude-3-haiku-20240307-v1:0
```

> **주의:** `LLM_API_KEY`는 `LLM_PROVIDER=openai`(기본값) 사용 시 필수입니다. 미설정 시 Spring Boot 기동 오류가 발생합니다.

---

## AI 서버 교체 방법

`llm_server`와 `rag_server`는 동일한 API 스펙을 구현하여 자유롭게 교체할 수 있습니다.

### 방법 1. 동일 포트(8000)로 스왑 교체 (권장)

1. 현재 실행 중인 AI 서버 터미널에서 `Ctrl+C`로 중지
2. 교체할 서버 디렉토리로 이동하여 동일 포트로 재실행:

```bash
cd rag_server       # 또는 llm_server
source venv/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

백엔드는 `http://localhost:8000`을 그대로 바라보므로 재시작 없이 즉시 교체됩니다.

### 방법 2. 다른 포트로 각각 실행 후 백엔드 설정 변경

두 서버를 동시에 실행하고 백엔드의 연동 대상을 전환합니다.

```bash
# 터미널 1
uvicorn main:app --port 8000 --reload   # llm_server

# 터미널 2
uvicorn main:app --port 8001 --reload   # rag_server
```

백엔드 환경 변수로 대상 서버를 지정합니다:

```bash
# macOS / Linux
export AI_SERVER_URL=http://localhost:8001
mvn spring-boot:run

# Windows PowerShell
$env:AI_SERVER_URL="http://localhost:8001"
.maven\apache-maven-3.9.6\bin\mvn.cmd spring-boot:run
```

또는 `backend/.env` 파일에 `AI_SERVER_URL=http://localhost:8001`을 기입합니다.

> **주의:** 교체한 AI 서버의 `.env` 파일에 `BACKEND_URL`이 실제 Spring Boot 주소(`http://localhost:8080`)와 일치하는지 반드시 확인하세요.

---

## RAG 지식베이스

`rag_server`는 아래 QA/보안 표준 문서를 기반으로 구축된 지식베이스를 검색에 활용합니다. 총 **184청크 / 7종 출처**이며, 서버 기동 시 Qdrant에 적재됩니다.

| 지식베이스 | 내용 | 청크 수 |
|-----------|------|--------|
| MS Playbook | Microsoft 테스트 플레이북 | 36 |
| OWASP | 웹 보안 취약점 및 대응 방법 | 34 |
| ISTQB | 소프트웨어 테스팅 국제 표준 자격 지식 카드 | 29 |
| Playwright | E2E 테스트 자동화 모범 사례 | 28 |
| Atlassian | 애자일 QA 및 프로세스 관리 지식 카드 | 24 |
| Cypress | E2E 테스트 베스트 프랙티스 | 22 |
| NIST SAMATE | 소프트웨어 보증 및 정적 분석 기준 | 11 |

문서 청크와 지식카드는 **하나의 Qdrant 컬렉션**에 함께 저장되어 단일 유사도 검색으로 같이 랭킹됩니다. 지식카드는 고정 `file_id`(`__knowledge_base__`)를 쓰므로 사용자 문서 삭제 시 영향받지 않습니다.

---

## API 엔드포인트 요약

### AI Server (rag_server / llm_server) — Port 8000

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/analysis/trigger` | 비동기 분석 작업 트리거 (202 Accepted) |
| `POST` | `/analyze` | 동일 기능의 대체 경로 |
| `POST` | `/api/files/preprocess` | 파일 파싱 가능 여부 사전 검증 |
| `DELETE` | `/api/vectors/{fileId}` | Qdrant 벡터 청크 삭제 — `metadata.file_id` 필터 기반 (rag_server 전용) |
| `POST` | `/api/eval/generate` | 품질 평가용 동기 생성 (`evaluation/eval_runner.py`가 호출) |
| `GET` | `/metrics` | Prometheus 메트릭 (토큰 Counter, 처리 시간 Histogram) |
| `GET` | `/health` | 서버 상태 확인. rag_server는 Qdrant 연결 실패 시 `503` 반환 |

**분석 트리거 요청 예시:**
```json
{
  "analysisId": "ANL-2026-0001",
  "projectId": "PRJ-2026-0001",
  "s3Paths": ["projects/PRJ-2026-0001/requirements.pdf"],
  "qaPerspectives": ["SECURITY", "PERFORMANCE"],
  "customPrompt": "JWT 인증 관련 예외 시나리오를 중점적으로 도출해 줘.",
  "llmApiKey": "sk-proj-..."
}
```

### Backend (Spring Boot) — Port 8080

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/projects` | 프로젝트 목록 조회 (S3 자동 동기화 트리거) |
| `POST` | `/api/projects` | 신규 프로젝트 생성 |
| `POST` | `/api/files/upload` | 명세 문서 업로드 (MinIO 저장) |
| `POST` | `/api/analysis/start` | AI 분석 시작 |
| `GET` | `/api/analysis/{id}` | 분석 결과 폴링 |
| `POST` | `/api/analysis/callback` | AI 서버 웹훅 수신 (내부 전용) |
| `GET` | `/api/reports/{id}` | 보고서 다운로드 |

---

## 테스트 실행

백엔드 JUnit 5 통합 테스트:

```bash
cd backend

# macOS / Linux
mvn test -Dtest=S3SyncTests           # S3 메타데이터 기반 DB 복구 검증
mvn test -Dtest=AnalysisJobEntityTests # H2 스키마 및 콜백 수집 로직 검증

# Windows
.maven\apache-maven-3.9.6\bin\mvn.cmd test -Dtest=S3SyncTests
.maven\apache-maven-3.9.6\bin\mvn.cmd test -Dtest=AnalysisJobEntityTests
```

rag_server pytest (총 54개):

```bash
cd rag_server

# 단위 테스트 — Qdrant 로컬 모드(:memory:) + 가짜 임베딩. 서버·모델 다운로드 불필요
python -m pytest -m "not integration"

# 통합 테스트 — 실제 Qdrant 서버 대상
QDRANT_URL=http://localhost:6333 python -m pytest test_integration_qdrant.py -m integration
```

CI(`.github/workflows/rag-tests.yml`)는 Qdrant 서비스 컨테이너를 띄워 두 갈래를 모두 실행하며, 통합 테스트가 skip되거나 0건 수집되면 워크플로우를 실패시킵니다.

AI 서버 수동 연동 검증 (cURL):

```bash
curl -X POST "http://localhost:8000/api/analysis/trigger" \
  -H "Content-Type: application/json" \
  -d '{
    "analysisId": "ANL-TEST-001",
    "projectId": "PRJ-TEST-001",
    "s3Paths": [],
    "llmApiKey": "sk-fake-test-key"
  }'
```

---

## 주요 기능

### S3 메타데이터 기반 DB 자동 복구

로컬 H2 DB가 초기화되어도 MinIO에 적재된 파일의 S3 User Metadata를 읽어 프로젝트 정보(이름, 설명, GitHub URL, 브랜치 등)를 완전 복구합니다. `GET /api/projects` 호출 시 `S3SyncService`가 자동으로 스캔 및 복원합니다.

### 동적 API Key 주입

프론트엔드 `localStorage`에 저장된 LLM API Key가 분석 트리거 시점에 백엔드를 거쳐 AI 서버까지 안전하게 전달됩니다. AI 서버는 키가 없을 경우 서버 환경 변수(`OPENAI_API_KEY`)로 폴백합니다.

### 인증 오류 실시간 피드백

API Key 만료/오류 발생 시 AI 서버가 `FAILED` 콜백을 즉시 전송하고, 프론트엔드 폴링이 중단되어 로딩 모달에 붉은색 경고 메시지와 함께 설정 화면 복귀 인터랙션이 노출됩니다.

---

## 모듈별 상세 가이드

각 모듈의 API 명세, 디렉토리 구조, 개발자 노트는 각 모듈 README를 참고하세요.

- [Backend (Spring Boot)](./backend/README.md)
- [Frontend (React)](./frontend/README.md)
- [LLM Server (FastAPI)](./llm_server/README.md)
- [RAG Server (FastAPI)](./rag_server/README.md)

추가 문서:
- [배포 가이드](./deploy_guide.md)
- [트러블슈팅](./troubleshooting.md)

---

## 개발자 팁

**H2 DB 초기화:** 스키마 변경이나 데이터 꼬임 발생 시 백엔드를 종료하고 `backend/data/yeonam_db.mv.db` 파일을 삭제한 뒤 재시작하면 `schema.sql` 기반으로 DB가 재생성됩니다.

**MinIO 연결 오류:** 백엔드 부팅 시 MinIO 버킷 존재 여부를 확인하고 자동 생성합니다. S3 연결 오류 발생 시 Docker Container의 MinIO(포트 9000)가 정상 실행 중인지 확인하세요.

**MOCK 모드 활용:** API 비용 없이 AI 서버(rag_server/llm_server) 파이프라인을 테스트하려면 AI 서버 `.env`에서 `MOCK_LLM=true`로 설정하세요(기본값은 `false`). 단, 백엔드(Spring Boot) 자체 LLM 클라이언트(보완 시나리오 생성)는 Mock 모드가 없으므로 `LLM_API_KEY` 설정이 필수입니다.

**Qdrant 컬렉션 초기화:** 임베딩 모델을 바꾸면 벡터 차원이 달라져 rag_server가 "벡터 차원 불일치" 예외로 기동에 실패합니다. 이는 의도된 동작이며, 기존 컬렉션을 삭제하고 재적재해야 합니다.
```bash
curl -X DELETE http://localhost:6333/collections/yeonam_knowledge
```
