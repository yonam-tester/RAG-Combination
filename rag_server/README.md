# 🐍 Yeonam Tester - RAG Analysis Server (FastAPI)

연암 테스터(Yeonam Tester)의 RAG(Retrieval-Augmented Generation) 파이프라인을 전담하는 FastAPI 모듈입니다. 업로드된 문서를 파싱·청킹해 Qdrant에 색인하고, 요구사항별로 근거를 검색해 테스트 케이스 생성을 조율합니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **Framework**: FastAPI (Uvicorn)
- **Language**: Python 3.10+
- **RAG 프레임워크**: LangChain (`langchain-text-splitters`, `langchain-qdrant`, LCEL)
- **Vector DB**: Qdrant (외부 컨테이너, 영속 스토리지)
- **Embedding**: HuggingFace `all-MiniLM-L6-v2` (로컬 CPU 실행, 384차원)
- **LLM 호출**: LiteLLM SDK (`litellm.acompletion`) — 별도 프록시 서버는 사용하지 않습니다
- **Object Storage Client**: boto3 (MinIO / AWS S3 연동)
- **Document Parsers**: PyMuPDF(`fitz`), python-docx

---

## 🔍 RAG 파이프라인 파라미터

| 단계 | 구현 | 설정값 |
|------|------|--------|
| 청킹 | `RecursiveCharacterTextSplitter` | chunk size 1000 / overlap 150 |
| 구분자 우선순위 | 문단 → 줄 → 문장 → 어절 | `\n\n`, `\n`, `. `, `。`, `! `, `? `, ` `, `` |
| 임베딩 | `all-MiniLM-L6-v2` | 384차원 |
| 거리 척도 | Qdrant COSINE | — |
| 검색 | 단일 벡터 유사도 검색 | top-k 5, score threshold 0.35 |
| 프롬프트 주입 | 상위 근거만 XML 태그로 구조화 | 최대 3건, 프롬프트 6000자 상한 |

리랭킹(reranking)과 BM25 하이브리드 검색은 구현되어 있지 않습니다.

---

## 📂 주요 디렉토리 구조 (Directory Structure)

- `main.py`: FastAPI 엔드포인트 라우팅, 작업 큐 접수, 파이프라인 6단계 오케스트레이션 및 `pipelineTrace` 생성
- `queue_manager.py`: `asyncio.Queue` 기반 인메모리 대기열 + 단일 백그라운드 워커(분석 작업 직렬 처리)
- `document_parser.py`: S3(MinIO)에서 문서 원본을 다운로드해 텍스트 추출 (PDF, DOCX, TXT, MD)
- `text_chunker.py`: 한국어 전처리(페이지 번호·머리말 제거, 섹션 제목 추적) 후 길이 기반 분할 및 결정적 `chunk_id` 부여
- `vector_store.py`: **벡터DB에 대한 유일한 접근 지점.** Qdrant 컬렉션 생성/검증, 멱등 upsert, `file_id` 기반 삭제, 유사도 검색
- `ingestion.py`: 정적 QA 지식카드(`rag_chunks.jsonl` → `knowledge_base/*.json` → 기본 세트 순 우선순위)를 서버 기동 시 Qdrant에 적재
- `retriever.py`: 요구사항 텍스트로 근거 청크를 검색. 문서 청크와 지식카드가 같은 컬렉션에 있어 한 번의 검색으로 함께 랭킹됩니다
- `requirement_extractor.py`: 문서 텍스트에서 테스트 가능한 기능 요구사항 목록 추출
- `prompt_builder.py`: RAG 프롬프트 조립 + LCEL 체인(litellm 호출 → `JsonOutputParser` → 필드 보정)
- `webhook_sender.py`: 분석 성공/실패를 Spring Boot 콜백 엔드포인트로 전송 (지수 백오프 최대 3회 재시도)
- `embedded/generate_rag_chunks.py`: `knowledge_base/*.json`을 임베딩용 청크(`rag_chunks.jsonl`)로 변환하는 오프라인 스크립트

---

## 🌟 주요 설계 결정

1. **멱등 upsert로 중복 적재 방지**
   - `chunk_id`를 `(file_id, 순번)`으로 결정적으로 만들고, 고정 네임스페이스 기반 `uuid5`로 Qdrant point ID를 생성합니다.
   - 같은 문서를 몇 번 재처리해도 같은 point를 덮어쓰므로 벡터가 누적되지 않습니다.
2. **재색인 없는 파일 단위 삭제**
   - 컬렉션 생성 시 `metadata.file_id`에 payload 인덱스를 함께 만듭니다.
   - `/api/vectors/{fileId}` 호출 시 해당 파일에서 유래한 청크만 필터로 즉시 삭제합니다. 전체 재빌드가 필요 없습니다.
3. **조용한 폴백을 두지 않는 fail-fast**
   - 기동 시 지식카드 적재가 실패하거나 임베딩 모델 로드가 실패하면 예외를 전파해 서버 기동을 실패시킵니다.
   - 기존 컬렉션의 벡터 차원이 현재 임베딩 모델과 다르면 명시적 예외로 중단합니다(임베딩 모델 교체 시 검색이 조용히 망가지는 것을 방지).
   - LLM 호출·응답 파싱 실패도 전파합니다. mock 테스트케이스로 폴백하면 사용자가 문서와 무관한 가짜 결과를 정상 분석 결과로 받게 됩니다.
4. **요구사항 간 근거 중복 제거**
   - 한 분석 작업 안에서 앞선 요구사항이 사용한 `chunk_id`를 제외하고 검색해, 여러 요구사항이 같은 근거로 유사한 테스트케이스를 만드는 것을 줄입니다.
5. **사용자 지정 API Key 동적 주입**
   - 백엔드에서 전달받은 `llmApiKey`가 있으면 `litellm.acompletion` 호출 시 명시적으로 주입합니다. 없으면 서버 환경변수로 폴백합니다.
   - `AuthenticationError`는 명시적으로 캐치해 `FAILED` 콜백에 사유를 담아 전송합니다.

---

## 🚀 로컬 실행 가이드

### 1. 가상환경 설정 및 의존성 설치

```bash
cd rag_server

# 가상환경 생성
python -m venv venv

# 활성화 (macOS/Linux)
source venv/bin/activate
# 활성화 (Windows PowerShell)
.\venv\Scripts\Activate.ps1

# 의존성 설치 (CPU 전용 torch 휠 사용 — CUDA 휠은 수 GB)
pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt
```

> `requirements.prod.txt`는 컨테이너 이미지용입니다. `langchain-huggingface`가 `sentence-transformers` → `torch`를 전이 의존으로 끌어옵니다.

### 2. Qdrant 실행

RAG 서버는 외부 Qdrant를 필요로 합니다. 호스트에서 `uvicorn`으로 실행할 때는 포트를 발행한 단독 컨테이너를 씁니다.

```bash
docker run -d --name qdrant-dev -p 6333:6333 qdrant/qdrant:v1.18.0
```

> compose의 `qdrant` 서비스는 `expose`만 선언해 컨테이너 네트워크 내부에서만 접근됩니다. 전체 스택을 컨테이너로 띄우는 경우 `rag-server`가 `http://qdrant:6333`으로 접근하므로 별도 조치가 필요 없지만, 호스트 실행 시에는 위처럼 포트 발행이 필요합니다.

단위 테스트만 돌릴 때는 Qdrant가 필요 없습니다 (`QDRANT_URL=:memory:` 로컬 모드).

### 3. 지식베이스 청크 생성 (최초 1회)

```bash
# rag_server/ 디렉토리에서
python embedded/generate_rag_chunks.py
```

`knowledge_base/*.json`을 읽어 `rag_chunks.jsonl`을 생성합니다. 서버 기동 시 이 파일이 자동으로 Qdrant에 적재됩니다.

### 4. 환경변수 설정

`.env.example`을 복사해 `.env`를 구성합니다.

```env
MOCK_LLM=false                    # true면 LLM 호출을 mock으로 대체 (기본값 false)
BACKEND_URL=http://localhost:8080 # Spring Boot 웹훅 콜백 주소
LLM_MODEL=gpt-4o-mini             # 실제 호출 시 사용할 모델
EMBEDDING_MODEL=all-MiniLM-L6-v2  # 로컬 임베딩 모델
QDRANT_URL=http://localhost:6333  # ':memory:'로 두면 서버 없이 로컬 모드(테스트용)
QDRANT_COLLECTION=yeonam_knowledge
# OPENAI_API_KEY=your_key_here    # MOCK_LLM=false이고 요청에 키가 없을 때 사용
```

### 5. 서버 실행

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 🧪 테스트 실행

테스트는 실행 환경에 따라 두 갈래로 나뉩니다.

```bash
# 단위 테스트 — Qdrant 로컬 모드(:memory:) + 가짜 임베딩. 서버·모델 다운로드 불필요
python -m pytest -m "not integration"

# 통합 테스트 — 실제 Qdrant 서버 대상
QDRANT_URL=http://localhost:6333 python -m pytest test_integration_qdrant.py -m integration
```

`QDRANT_URL`이 설정되지 않으면 통합 테스트는 skip됩니다. CI(`.github/workflows/rag-tests.yml`)는 통합 테스트가 skip되거나 0건 수집되면 워크플로우를 실패시켜, 실서버 검증이 조용히 빠지는 것을 막습니다.

---

## 🔌 API 엔드포인트 사양

### 1. 비동기 분석 작업 트리거

- **Endpoint**: `POST /api/analysis/trigger` (대체 경로: `POST /analyze`)
- **Request Body**:
```json
{
  "analysisId": "ANL-TEST-001",
  "projectId": "PRJ-TEST-001",
  "s3Paths": ["projects/PRJ-TEST-001/DOC-001_specification.pdf"],
  "qaPerspectives": ["SECURITY", "PERFORMANCE"],
  "customPrompt": "사용자 입력 검증 실패 시나리오를 심층적으로 도출해 줘.",
  "llmApiKey": "sk-proj-..."
}
```
- **Response (202 Accepted)**:
```json
{ "message": "Job accepted and queued for RAG analysis." }
```

작업 완료 시 백엔드 `/api/internal/analysis/{analysisId}/callback`으로 결과를 콜백합니다. 콜백 payload에는 단계별 소요 시간이 담긴 `pipelineTrace`(PARSE / CHUNK / INDEX / EXTRACT / RETRIEVE)가 포함됩니다.

### 2. 파일 파싱 가능 여부 사전 검증

- **Endpoint**: `POST /api/files/preprocess`
- **Request Body**: `{ "fileId": "DOC-001", "s3Path": "projects/.../spec.pdf" }`
- 파싱 실패 시 `422`와 사유를 반환합니다.

### 3. 벡터 청크 삭제

- **Endpoint**: `DELETE /api/vectors/{fileId}`
- `metadata.file_id` 필터로 해당 파일의 청크만 삭제합니다. 지식카드(`__knowledge_base__`)는 영향받지 않습니다.
- **Response (200 OK)**:
```json
{ "status": "success", "message": "Vectors for file DOC-xxxx deleted." }
```

### 4. 품질 평가용 동기 생성

- **Endpoint**: `POST /api/eval/generate`
- `evaluation/eval_runner.py`가 LLM 단독 생성과 RAG 생성을 비교할 때 호출합니다.
- **Response**: `{ "output": "...", "token_count": 0, "duration_ms": 0 }`

### 5. Prometheus 메트릭

- **Endpoint**: `GET /metrics`
- `rag_tokens_total`(Counter), `rag_request_duration_seconds`(Histogram, 버킷 1/5/10/30/60/120초)를 노출합니다.

### 6. 헬스 체크

- **Endpoint**: `GET /health`
- Qdrant 연결 상태를 그대로 반영합니다. 연결 실패 시 **503**을 반환합니다.
```json
{
  "status": "healthy",
  "mock_llm": false,
  "qdrant": "up",
  "queue_size": 0
}
```
