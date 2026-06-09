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
   └───────┬──────┘    │  │  (단순 LLM)  │  │  (RAG + FAISS)│ │
           │           │  └──────────────┘  └───────────────┘ │
           └───────────│ Document Download                      │
                       └───────────────────────────────────────┘
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
| **RAG Server** | Python 3.10+, FastAPI, FAISS, sentence-transformers, LiteLLM |
| **LLM Server** | Python 3.10+, FastAPI, LiteLLM, pypdf, python-docx |
| **Storage** | MinIO (S3 호환), H2 File Mode DB |

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

### Step 1. MinIO (로컬 S3) 실행

프로젝트 루트에서 Docker Compose로 MinIO를 백그라운드 실행합니다.

```bash
docker-compose up -d
```

| 엔드포인트 | 주소 | 용도 |
|------------|------|------|
| S3 API | `http://localhost:9000` | 백엔드 / AI 서버 연동 |
| Console UI | `http://localhost:9001` | 관리 콘솔 (계정: `minioadmin` / `minioadmin`) |

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

실행 완료 후 `rag_server/rag_chunks.jsonl` 파일이 생성되며, 서버 구동 시 자동으로 로드됩니다.

#### 2-3. 환경 변수 설정

`.env.example`을 복사하여 `.env`를 생성합니다.

```bash
cp .env.example .env
```

**로컬 테스트 모드 (API 비용 없음 - 권장):**

```env
MOCK_RAG=false
MOCK_LLM=true
BACKEND_URL=http://localhost:8080
```

**실제 LLM 연동 모드:**

```env
MOCK_RAG=false
MOCK_LLM=false
LLM_MODEL=gpt-4o-mini
BACKEND_URL=http://localhost:8080
```

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

브라우저에서 `http://localhost:5173`으로 접속합니다.

---

### 포트 요약

| 서비스 | 포트 | 설명 |
|--------|------|------|
| Frontend (React) | 5173 | Vite 개발 서버 |
| Backend (Spring Boot) | 8080 | REST API 서버 |
| AI Server (FastAPI) | 8000 | RAG Server 또는 LLM Server |
| MinIO S3 API | 9000 | 오브젝트 스토리지 API |
| MinIO Console | 9001 | 웹 관리 콘솔 |

---

## 환경 변수 설정

### rag_server / llm_server `.env`

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MOCK_LLM` | `true` | `true`면 LLM 호출을 Mock으로 처리 (API 비용 없음) |
| `MOCK_RAG` | `true` | `true`면 S3 다운로드/파싱/인덱싱을 건너뜀 (rag_server 전용) |
| `BACKEND_URL` | `http://localhost:8080` | 웹훅 콜백을 보낼 백엔드 주소 |
| `LLM_MODEL` | `gpt-4o-mini` | 실제 LLM 호출 시 사용할 모델명 |
| `EMBEDDING_PROVIDER` | `local` | 임베딩 제공자 (`local` = sentence-transformers) |
| `EMBEDDING_MODEL` | `all-MiniLM-L6-v2` | 로컬 임베딩 모델명 |

### backend `.env`

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AI_SERVER_URL` | `http://localhost:8081` | 연동할 AI 서버 주소 |

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

`rag_server`는 아래 QA/보안 표준 문서를 기반으로 구축된 지식베이스를 검색에 활용합니다.

| 지식베이스 | 내용 |
|-----------|------|
| ISTQB | 소프트웨어 테스팅 국제 표준 자격 지식 카드 |
| OWASP | 웹 보안 취약점 및 대응 방법 |
| Playwright | E2E 테스트 자동화 모범 사례 |
| Cypress | E2E 테스트 베스트 프랙티스 |
| NIST SAMATE | 소프트웨어 보증 및 정적 분석 기준 |
| Atlassian | 애자일 QA 및 프로세스 관리 지식 카드 |
| MS Playbook | Microsoft 테스트 플레이북 |

---

## API 엔드포인트 요약

### AI Server (rag_server / llm_server) — Port 8000

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/analysis/trigger` | 비동기 분석 작업 트리거 (202 Accepted) |
| `POST` | `/analyze` | 동일 기능의 대체 경로 |
| `POST` | `/api/files/preprocess` | 파일 파싱 가능 여부 사전 검증 |
| `DELETE` | `/api/vectors/{fileId}` | FAISS 벡터 청크 삭제 (rag_server 전용) |
| `GET` | `/health` | 서버 상태 및 Mock 모드 확인 |

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

**MOCK 모드 활용:** API 비용 없이 전체 파이프라인을 테스트하려면 AI 서버 `.env`에서 `MOCK_LLM=true`, `MOCK_RAG=true`로 설정하세요.
