# 🐍 Yeonam Tester - RAG Analysis Server (FastAPI)

연암 테스터(Yeonam Tester)의 핵심 AI 분석 및 RAG(Retrieval-Augmented Generation) 파이프라인을 전담하는 FastAPI 백엔드 모듈입니다. 업로드된 문서를 파싱하고, 로컬 FAISS 벡터 스토어에 인덱싱 및 검색을 수행하며, 요구사항 추출 및 근거(Evidence) 기반 테스트 케이스 생성을 조율합니다.

---

## 🛠️ 기술 스택 (Tech Stack)

- **Framework**: FastAPI (Uvicorn)
- **Language**: Python 3.10+
- **LLM Gateway**: LiteLLM (OpenAI, Anthropic 등 다양한 AI 모델 통합 인터페이스)
- **Vector DB / Search**: FAISS (Facebook AI Similarity Search)
- **Object Storage Client**: boto3 (MinIO S3 연동)
- **Document Parsers**: pypdf, python-docx

---

## 📂 주요 디렉토리 구조 (Directory Structure)

- `main.py`: FastAPI 엔드포인트 라우팅, 작업 큐 접수 및 백그라운드 태스크 제어
- `queue_manager.py`: `asyncio.Queue` 기반의 인메모리 비동기 대기열 및 백그라운드 작업 처리
- `document_parser.py`: S3(MinIO)로부터 문서 원본을 다운로드하여 텍스트를 추출 (PDF, DOCX, TXT, MD 지원)
- `text_chunker.py`: 추출된 텍스트를 고정 크기로 나누고 구조화 데이터(표, 목록)의 일관성을 유지하며 분할
- `vector_db_manager.py`: 분할된 텍스트 청크들을 로컬 FAISS 인덱스에 적재 및 삭제(동적 연쇄 소거) 관리
- `retriever.py`: 요구사항 맥락에 적합한 문서 조각(증적 근거)을 벡터 데이터베이스에서 검색 및 순위 재지정
- `requirement_extractor.py`: 주입된 문서 텍스트 전체에서 테스트 가능한 핵심 기능 요구사항 목록 추출
- `prompt_builder.py`: LLM 호출을 위한 RAG 결합 프롬프트 조립 및 사용자 정의 API Key 주입 래퍼
- `webhook_sender.py`: 분석 작업의 성공/실패 여부를 스프링 부트 백엔드 콜백 엔드포인트로 웹훅 전송

---

## 🌟 최근 추가 개선 스펙

1. **사용자 지정 API Key 동적 주입 및 보안 강화**
   - 백엔드에서 전달받은 `llmApiKey`가 존재하는 경우, `litellm.acompletion` 호출 시 API Key 매개변수로 명시적 주입하여 구동됩니다.
   - 프론트엔드와 백엔드에 키가 등록되지 않았을 경우, 디폴트 동작으로서 RAG 서버 내부 `.env` 파일에 기록된 로컬 환경변수(`OPENAI_API_KEY`)를 안전하게 폴백하여 동작시킵니다.
2. **인증 예외 전파 및 무결성 실패 콜백 일원화**
   - LiteLLM을 통해 LLM 호출 중 API Key가 만료되거나 유효하지 않아 발생하는 `AuthenticationError` 계열의 예외를 명시적으로 캐치합니다.
   - 예외 발생 시, 분석 진행 상태를 `FAILED`로 마킹하고 명확한 실패 사유 메시지(`errorMessage`)를 담아 백엔드로 콜백을 즉시 전송하여 에러 피드백을 사용자에게 정밀 렌더링합니다.
3. **벡터 DB 동적 연쇄 소거 기능**
   - 문서가 삭제되거나 프로젝트가 파기되는 시점에 `/api/vectors/{fileId}` API 요청을 수신하여, 해당 문서에 매핑되어 인덱싱된 모든 벡터 청크들을 FAISS 스페이스에서 즉시 연쇄 소거합니다.

---

## 🚀 로컬 실행 가이드

### 1. 가상환경 설정 및 의존성 설치
`rag_server/` 디렉토리로 이동하여 가상환경을 활성화하고 필요 패키지를 설치합니다:

```bash
cd rag_server

# 가상환경 생성 (Windows)
python -m venv venv

# 가상환경 활성화 (Windows PowerShell)
.\venv\Scripts\Activate.ps1

# 가상환경 활성화 (macOS/Linux)
source venv/bin/activate

# 의존성 패키지 설치
pip install -r requirements.txt
```

### 2. 환경변수 설정
`.env.example` 파일을 복사하여 `.env` 파일을 구성합니다:

```env
MOCK_RAG=true                     # 로컬 가상 RAG 테스트 모드 활성화 여부
MOCK_LLM=true                     # 로컬 가상 LLM 호출 테스트 모드 활성화 여부
BACKEND_URL=http://localhost:8080 # 스프링 부트 백엔드 웹훅 콜백 주소
LLM_MODEL=gpt-4o-mini             # 실제 호출 시 사용할 기본 LLM 모델 명칭
# OPENAI_API_KEY=your_key_here    # MOCK_LLM=false 설정 시 사용할 로컬 API 키
```

### 3. FastAPI 서버 실행
포트 `8000`번(또는 백엔드 `application.yml` 설정 포트)으로 개발 서버를 구동합니다:
```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 🔌 API 엔드포인트 사양

### 1. 비동기 분석 작업 트리거 API
- **Endpoint**: `POST /api/analysis/trigger` (또는 `POST /analyze`)
- **Request Body JSON**:
```json
{
  "analysisId": "ANL-TEST-001",
  "projectId": "PRJ-TEST-001",
  "s3Paths": ["projects/PRJ-TEST-001/DOC-001_specification.pdf"],
  "qaPerspectives": ["SECURITY", "PERFORMANCE"],
  "customPrompt": "사용자 입력 검증 실패 시나리오를 심층적으로 도출해 줘.",
  "llmApiKey": "sk-proj-..." // (선택) 프론트엔드에서 등록한 dynamic API Key
}
```
- **Response (202 Accepted)**:
```json
{
  "message": "Job accepted and queued for RAG analysis."
}
```

### 2. 벡터 DB 청크 동적 소거 API
- **Endpoint**: `DELETE /api/vectors/{fileId}`
- **Response (200 OK)**:
```json
{
  "status": "success",
  "message": "Vectors for file DOC-xxxx deleted."
}
```

### 3. 서버 헬스 체크 API
- **Endpoint**: `GET /health`
- **Response (200 OK)**:
```json
{
  "status": "healthy",
  "mock_llm": true,
  "mock_rag": true,
  "queue_size": 0
}
```
